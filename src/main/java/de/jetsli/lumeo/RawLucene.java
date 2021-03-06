package de.jetsli.lumeo;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongField;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocsEnum;
import org.apache.lucene.index.IndexReaderContext;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LogByteSizeMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.NRTManager;
import org.apache.lucene.search.NRTManager.TrackingIndexWriter;
import org.apache.lucene.search.NRTManagerReopenThread;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jetsli.lumeo.util.IndexOp;
import de.jetsli.lumeo.util.LuceneHelper;
import de.jetsli.lumeo.util.Mapping;
import de.jetsli.lumeo.util.SearchExecutor;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.*;

/**
 * Uses a buffer to accumulate uncommitted state. Should stay independent of Blueprints API.
 *
 * Minor impressions taken from
 * http://code.google.com/p/graphdb-load-tester/source/browse/trunk/src/com/tinkerpop/graph/benchmark/index/LuceneKeyToNodeIdIndexImpl.java
 *
 * -> still use batchBuffer to support realtime get and to later support versioning -> use near real
 * time reader, no need for commit -> no bloomfilter then it is 1 sec (>10%) faster for testIndexing
 * and less memory usage TODO check if traversal benchmark is also faster
 *
 * @author Peter Karich, info@jetsli.de
 */
public class RawLucene {

    // of type long, for more efficient storage of node references
    public static final String ID = "_id";
    // of type String, can be defined by the user
    public static final String UID = "_uid";
    // of type String
    public static final String TYPE = "_type";
    public static final String EDGE_OUT = "_eout";
    public static final String EDGE_IN = "_ein";
    public static final String EDGE_LABEL = "_elabel";
    public static final String VERTEX_OUT = "_vout";
    public static final String VERTEX_IN = "_vin";
    public static final Version VERSION = Version.LUCENE_40;
    private TrackingIndexWriter writer;
    private Directory dir;
    private NRTManager nrtManager;
    //Avoid Lucene performing "mega merges" with a finite limit on segments sizes that can be merged
    private int maxMergeMB = 3000;
    private volatile long luceneOperations = 0;
    private long failedLuceneReads = 0;
    private long successfulLuceneReads = 0;
    private double ramBufferSizeMB = 128;
    private int termIndexIntervalSize = 512;
    private final ReadWriteLock indexRWLock = new ReentrantReadWriteLock();
    // id -> indexOp (create, update, delete)    
    // we could group indexop and same type (same analyzer) to make indexing faster    
    private final Map<Long, Map<Long, IndexOp>> realTimeCache = new ConcurrentHashMap<Long, Map<Long, IndexOp>>();
    private Logger logger = LoggerFactory.getLogger(getClass());
    private Map<String, Mapping> mappings = new ConcurrentHashMap<String, Mapping>(2);
    private Mapping defaultMapping = new Mapping("_default");
    private String name;
    private boolean closed = false;
    private FlushThread flushThread;
    private NRTManagerReopenThread reopenThread;
    private volatile long latestGen = -1;
    // If there are waiting searchers how long should reopen takes?
    double incomingSearchesMaximumWaiting = 0.03;
    // If there are no waiting searchers reopen it less frequent.
    // This also controls how large the realtime cache can be. less frequent reopens => larger cache
    double ordinaryWaiting = 5.0;

    public RawLucene(String path) {
        try {
            // if indexing rate is lowish but reopen rate is highish
            // dir = new NRTCachingDirectory(FSDirectory.open(new File(path)), 5, 60);
            dir = FSDirectory.open(new File(path));
            name = "fs:" + path + " " + dir.toString();
        } catch (IOException ex) {
            throw new RuntimeException("cannot open lucene directory located at " + path + " error:" + ex.getMessage());
        }
    }

    public RawLucene(Directory directory) {
        dir = directory;
        name = "mem " + dir.toString();
    }

    public RawLucene init() {
        indexLock();
        try {
            if (closed)
                throw new IllegalStateException("Already closed");

            if (writer != null)
                throw new IllegalStateException("Already initialized");

            // release locks when started
            if (IndexWriter.isLocked(dir)) {
                logger.warn("index is locked + " + name + " -> releasing lock");
                IndexWriter.unlock(dir);
            }
            IndexWriterConfig cfg = new IndexWriterConfig(VERSION, defaultMapping.getCombinedAnalyzer());
            LogByteSizeMergePolicy mp = new LogByteSizeMergePolicy();
            mp.setMaxMergeMB(getMaxMergeMB());
            cfg.setRAMBufferSizeMB(ramBufferSizeMB);
            cfg.setTermIndexInterval(termIndexIntervalSize);
            cfg.setMergePolicy(mp);

            // TODO specify different formats for id fields etc
            // -> this breaks 16 of our tests!? Lucene Bug?
//            cfg.setCodec(new Lucene40Codec() {
//
//                @Override public PostingsFormat getPostingsFormatForField(String field) {
//                    return new Pulsing40PostingsFormat();
//                }
//            });

            // cfg.setMaxThreadStates(8);
            boolean create = !DirectoryReader.indexExists(dir);
            cfg.setOpenMode(create ? IndexWriterConfig.OpenMode.CREATE : IndexWriterConfig.OpenMode.APPEND);

            //wrap the writer with a tracking index writer
            writer = new TrackingIndexWriter(new IndexWriter(dir, cfg));

            nrtManager = new NRTManager(writer, new SearcherFactory() {
//              @Override
//              public IndexSearcher newSearcher(IndexReader reader) throws IOException {
//                //TODO do some kind of warming here?
//                return new IndexSearcher(reader);
//              }              
            });

            getCurrentRTCache(latestGen);
            int priority = Math.min(Thread.currentThread().getPriority() + 2, Thread.MAX_PRIORITY);
            flushThread = new FlushThread("flush-thread");
            flushThread.setPriority(priority);
            flushThread.setDaemon(true);
            flushThread.start();

            reopenThread = new NRTManagerReopenThread(nrtManager, ordinaryWaiting, incomingSearchesMaximumWaiting);
            reopenThread.setName("NRT Reopen Thread");
            reopenThread.setPriority(priority);
            reopenThread.setDaemon(true);
            reopenThread.start();
            return this;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            indexUnlock();
        }
    }

    long getId(Document doc) {
        return ((LongField) doc.getField(ID)).numericValue().longValue();
    }

    public Document findById(final long id) {
        //Check cache
        IndexOp result = getCurrentRTCache(latestGen).get(id);
        if (result != null) {
            if (result.type == IndexOp.Type.DELETE)
                return null;
            return result.document;
        }

        return searchSomething(new SearchExecutor<Document>() {

            @Override public Document execute(IndexSearcher searcher) throws Exception {
                // TODO optimize via indexReader.termDocsEnum !?
                IndexReaderContext trc = searcher.getTopReaderContext();
                AtomicReaderContext[] arc = trc.leaves();
                for (int i = 0; i < arc.length; i++) {
                    AtomicReader subreader = arc[i].reader();
                    DocsEnum docs = subreader.terms(UID).iterator(null).docs(subreader.getLiveDocs(), null, false);
                    if (docs != null) {
                        int docID = docs.nextDoc();
                        if (docID != DocsEnum.NO_MORE_DOCS) {
                            return subreader.document(docID);
                        }
                    }
                }
                return null;
            }
        });
    }

    public Document findByUserId(final String uId) {
        return searchSomething(new SearchExecutor<Document>() {

            @Override public Document execute(final IndexSearcher searcher) throws IOException {
                final BytesRef bytes = new BytesRef(uId);
                Document doc = null;
                //IndexReaderContext trc = searcher.getTopReaderContext();
                //trc.children();
                
                //TODO -MH  search subreaders - share common subreader code in findByID?

                //Hopefully Lucene should bail after collecting our result of 1
                TopDocs results = searcher.search(new TermQuery(new Term(UID, bytes)), 1);
                if (results.totalHits > 1) {
                    throw new IllegalStateException("Document with " + UID + "=" + uId + " not the only one");
                }
                if (results.totalHits == 1) {
                    doc = searcher.document(results.scoreDocs[0].doc, null);
                }

//                new MyGather(searcher.getIndexReader()) {
//
//                    @Override protected boolean runLeaf(int base, AtomicReader leaf) throws IOException {
//                        DocsEnum docs = leaf.termDocsEnum(leaf.getLiveDocs(), UID, bytes, false);
//                        if (docs == null)
//                            return true;
//
//                        int docID = docs.nextDoc();
//                        if (docID == DocsEnum.NO_MORE_DOCS)
//                            return true;
//
//                        if (docs.nextDoc() != DocsEnum.NO_MORE_DOCS)
//                            throw new IllegalStateException("Document with " + UID + "=" + uId + " not the only one");
//
//                        doc = searcher.doc(base + docID);
//                        return false;
//                    }
//                }.run();
                return doc;
            }
        });
    }

    public <T> T searchSomething(SearchExecutor<T> exec) {
        IndexSearcher searcher = nrtManager.acquire();
        try {
            return (T) exec.execute(searcher);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                nrtManager.release(searcher);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public boolean exists(long id) {
        return findById(id) != null;
    }

    public boolean existsUserId(String uId) {
        return findByUserId(uId) != null;
    }

    // not thread safe => only an estimation
    public int calcSize() {
        // TODO too many entries are reported        
        int unflushedEntries = 0;
        for (Entry<Long, Map<Long, IndexOp>> e : realTimeCache.entrySet()) {
            if (latestGen >= e.getKey())
                unflushedEntries = e.getValue().size();
        }
        return unflushedEntries;
    }

    public void close() {
        indexLock();
        try {
            flushThread.interrupt();
            reopenThread.close();
            flushThread.join();

            // force correct count of calcSize
//            waitUntilSearchable();
//            cleanUpCache(latestGen + 1, 0);

            closed = true;
            nrtManager.close();
            try {
                waitUntilSearchable();
//                writer.waitForMerges();
//                writer.commit();
            } catch (Exception ex) {
                logger.warn("Couldn't commit changes to writer", ex);
                writer.getIndexWriter().rollback();
            }
            writer.getIndexWriter().close();
            dir.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            indexUnlock();
        }
    }

    public Document createDocument(String uId, long id, Class cl) {
        Document doc = new Document();
        Mapping m = getMapping(cl.getSimpleName());
        doc.add(m.createField(RawLucene.TYPE, cl.getSimpleName()));
        doc.add(m.newUIdField(UID, uId));
        doc.add(m.newIdField(ID, id));
        return doc;
    }

    /**
     * Warning: Counts only docs already indexed - exclusive the realtime cache if not yet commited.
     */
    long count(Class cl, final String fieldName, Object val) {
        Mapping m = getMapping(cl);
        final BytesRef bytes = m.toBytes(fieldName, val);
        return searchSomething(new SearchExecutor<Long>() {

            @Override public Long execute(IndexSearcher searcher) throws Exception {
                // TODO optimize via indexReader.termDocsEnum !?
                TopDocs td = searcher.search(new TermQuery(new Term(fieldName, bytes)), 1);
                return (long) td.totalHits;
            }
        });
    }

    long removeById(final long id) {
        try {
            latestGen = writer.deleteDocuments(new Term(ID, LuceneHelper.newRefFromLong(id)));
            getCurrentRTCache(latestGen).put(id, new IndexOp(IndexOp.Type.DELETE));
            return latestGen;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public long fastPut(long id, Document newDoc) {
        try {
            String type = newDoc.get(TYPE);
            if (type == null)
                throw new UnsupportedOperationException("Document needs to have a type associated");
            Mapping m = getMapping(type);
            latestGen = writer.updateDocument(new Term(ID, LuceneHelper.newRefFromLong(id)),
                    newDoc, m.getCombinedAnalyzer());
            getCurrentRTCache(latestGen).put(id, new IndexOp(newDoc, IndexOp.Type.UPDATE));
            return latestGen;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public long put(String uId, long id, Document newDoc) {
        String type = newDoc.get(TYPE);
        if (type == null)
            throw new UnsupportedOperationException("Document needs to have a type associated");
        Mapping m = getMapping(type);
        if (newDoc.get(ID) == null)
            newDoc.add(m.newIdField(ID, id));

        if (newDoc.get(UID) == null)
            newDoc.add(m.newUIdField(UID, uId));

        return fastPut(id, newDoc);
    }

    void refresh() {
        try {
            // use waitForGeneration instead?
//            writer.commit();
            writer.getIndexWriter().commit();
            nrtManager.maybeRefreshBlocking();
//            nrtManager.waitForGeneration(latestGen, true);
        } catch (Exception ex) {
            throw new RuntimeException();
        }
    }

    /**
     * You'll need to call releaseUnmanagedSearcher afterwards
     */
    IndexSearcher newUnmanagedSearcher() {
        return nrtManager.acquire();
    }

    void releaseUnmanagedSearcher(IndexSearcher searcher) {
        try {
            nrtManager.release(searcher);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    void removeDoc(Document doc) {
        removeById(getId(doc));
    }

    void indexLock() {
        indexRWLock.writeLock().lock();
    }

    void indexUnlock() {
        indexRWLock.writeLock().unlock();
    }

    @Override public String toString() {
        return name;
    }

    void initRelation(Document edgeDoc, Document vOut, Document vIn) {
        long oIndex = getId(vOut);
        edgeDoc.add(defaultMapping.newIdField(VERTEX_OUT, oIndex));
        long iIndex = getId(vIn);
        edgeDoc.add(defaultMapping.newIdField(VERTEX_IN, iIndex));

        long eId = getId(edgeDoc);
        vOut.add(defaultMapping.newIdField(EDGE_OUT, eId));
        vIn.add(defaultMapping.newIdField(EDGE_IN, eId));

        fastPut(oIndex, vOut);
        fastPut(iIndex, vIn);
    }

    static String getVertexFieldForEdgeType(String edgeType) {
        if (EDGE_IN.equals(edgeType))
            return VERTEX_IN;
        else if (EDGE_OUT.equals(edgeType))
            return VERTEX_OUT;
        else
            throw new UnsupportedOperationException("Edge type not supported:" + edgeType);
    }

    /**
     * @return never null. Automatically creates a mapping if it does not exist.
     */
    public Mapping getMapping(Class cl) {
        return getMapping(cl.getSimpleName());
    }

    public Mapping getMapping(String type) {
        if (type == null)
            throw new NullPointerException("Type mustn't be empty!");

        Mapping m = mappings.get(type);
        if (m == null) {
            mappings.put(type, m = new Mapping(type));

            if (logger.isDebugEnabled())
                logger.debug("Created mapping for type " + type);
        }
        return m;
    }
    private Map<Long, IndexOp> tmpCache;
    private long tmpGen = -2;

    private Map<Long, IndexOp> getCurrentRTCache(long gen) {
        if (gen > tmpGen)
            synchronized (realTimeCache) {
                tmpGen = gen;
                tmpCache = new ConcurrentHashMap<Long, IndexOp>(100);
                realTimeCache.put(gen, tmpCache);
            }

        return tmpCache;
    }

    private class FlushThread extends Thread {

        public FlushThread(String name) {
            super(name);
        }

        @Override public void run() {
            Throwable exception = null;
            while (!isInterrupted()) {
                try {
                    cleanUpCache(latestGen);
                } catch (InterruptedException ex) {
                    exception = ex;
                    break;
                } catch (AlreadyClosedException ex) {
                    exception = ex;
                    break;
                } catch (OutOfMemoryError er) {
                    logger.error("Now closing writer due to OOM", er);
                    try {
                        writer.getIndexWriter().close();
                    } catch (Exception ex) {
                        logger.error("Error while closing writer", ex);
                    }
                    exception = er;
                    break;
                } catch (Exception ex) {
                    logger.error("Problem while flushing", ex);
                }
            }

            logger.debug("flush-thread interrupted, " + ((exception == null) ? "" : exception.getMessage())
                    + ", buffer:" + calcSize());
        }
    }

    /**
     * Nearly always faster than flush but slightly more expensive as it will force the nrtManager
     * to reopen a reader very fast
     */
    void waitUntilSearchable() {
        nrtManager.waitForGeneration(latestGen);
    }

    public void flush() {
        try {
            cleanUpCache(latestGen);
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Very slow compared to waitUntilSearchable but slightly more efficient (~3%) for indexing and
     * so it is suited for our background thread
     */
    void cleanUpCache(long gen) throws InterruptedException {
        cleanUpCache(gen, Math.round(ordinaryWaiting * 1000));
    }

    void cleanUpCache(long gen, long waiting) throws InterruptedException {
        if (nrtManager.getCurrentSearchingGen() >= gen) {
            // do not max out the CPU if called in a loop
            Thread.sleep(20);
            return;
        }

        // avoid nrtManager.waitForGeneration as we would force the reader to reopen too fast        
        Thread.sleep(waiting);
//        nrtManager.waitForGeneration(gen, true);
        int removed = 0;
        int removedItems = 0;
        Iterator<Entry<Long, Map<Long, IndexOp>>> iter = realTimeCache.entrySet().iterator();
        while (iter.hasNext()) {
            Entry<Long, Map<Long, IndexOp>> e = iter.next();
            if (e.getKey() < gen) {
                iter.remove();
                removed++;
                removedItems += e.getValue().size();
                e.getValue().clear();
            }
        }
//        if (removed > 0)
//            logger.info("removed objects " + removedItems + ", removed maps:" + removed + " older than gen:" + gen);
    }

    public double getRamBufferSizeMB() {
        return ramBufferSizeMB;
    }

    public void setRamBufferSizeMB(double ramBufferSizeMB) {
        this.ramBufferSizeMB = ramBufferSizeMB;
    }

    public int getTermIndexIntervalSize() {
        return termIndexIntervalSize;
    }

    public void setTermIndexIntervalSize(int termIndexIntervalSize) {
        this.termIndexIntervalSize = termIndexIntervalSize;
    }

    public void setMaxMergeMB(int maxMergeMB) {
        this.maxMergeMB = maxMergeMB;
    }

    public int getMaxMergeMB() {
        return maxMergeMB;
    }

    public long getLuceneAdds() {
        return luceneOperations;
    }

    public long getFailedLuceneReads() {
        return failedLuceneReads;
    }

    public long getSuccessfulLuceneReads() {
        return successfulLuceneReads;
    }

    public NRTManager getNrtManager() {
        return nrtManager;
    }    
}