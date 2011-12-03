package com.pannous.lumeo;

import com.tinkerpop.blueprints.pgm.AutomaticIndex;

import com.tinkerpop.blueprints.pgm.Element;

/**
 * @author Peter Karich, info@jetsli.de
 */
public class LuceneAutomaticIndex<T extends Element> extends LuceneIndex<T> implements AutomaticIndex<T> {

    public LuceneAutomaticIndex(final LuceneGraph graph, final Class<T> indexClass) {
        super(graph, indexClass);
    }

    @Override public Type getIndexType() {
        return Type.AUTOMATIC;
    }

    protected void autoUpdate(final String key, final Object newValue, final Object oldValue, final T element) {
        if (oldValue != null)
            this.remove(key, oldValue, element);

        put(key, oldValue, element);
    }

    protected void autoRemove(final String key, final Object oldValue, final T element) {
        remove(key, oldValue, element);        
    }
}