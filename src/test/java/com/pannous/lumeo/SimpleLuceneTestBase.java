/*
 *  Copyright 2011 Peter Karich info@jetsli.de
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.pannous.lumeo;

import com.pannous.lumeo.LuceneGraph;
import org.junit.After;
import org.junit.Before;

/**
 *
 * @author Peter Karich, info@jetsli.de
 */
public class SimpleLuceneTestBase {

    LuceneGraph g;

    @Before public void setUp() {
        g = new LuceneGraph();
    }

    @After public void tearDown() {
        g.shutdown();
    }

    protected void flushAndRefresh() {
        g.flush();
        g.refresh();
    }
}