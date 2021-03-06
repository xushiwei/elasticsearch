/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.unit.index.percolator;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.elasticsearch.cache.recycler.CacheRecyclerModule;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.inject.util.Providers;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNameModule;
import org.elasticsearch.index.analysis.AnalysisModule;
import org.elasticsearch.index.cache.IndexCacheModule;
import org.elasticsearch.index.codec.CodecModule;
import org.elasticsearch.index.engine.IndexEngineModule;
import org.elasticsearch.index.mapper.MapperServiceModule;
import org.elasticsearch.index.percolator.PercolatorExecutor;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.IndexQueryParserModule;
import org.elasticsearch.index.settings.IndexSettingsModule;
import org.elasticsearch.index.settings.IndexSettingsService;
import org.elasticsearch.index.similarity.SimilarityModule;
import org.elasticsearch.indices.query.IndicesQueriesModule;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.ThreadPoolModule;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import static org.elasticsearch.index.query.QueryBuilders.constantScoreQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 *
 */
@Test
public class PercolatorExecutorTests {

    private Injector injector;

    private PercolatorExecutor percolatorExecutor;

    @BeforeTest
    public void buildPercolatorService() {
        Settings settings = ImmutableSettings.settingsBuilder()
                //.put("index.cache.filter.type", "none")
                .build();
        Index index = new Index("test");
        injector = new ModulesBuilder().add(
                new CacheRecyclerModule(settings),
                new IndexSettingsModule(index, settings),
                new CodecModule(settings),
                new SettingsModule(settings),
                new ThreadPoolModule(settings),
                new ScriptModule(settings),
                new IndicesQueriesModule(),
                new MapperServiceModule(),
                new IndexCacheModule(settings),
                new AnalysisModule(settings),
                new IndexEngineModule(settings),
                new SimilarityModule(settings),
                new IndexQueryParserModule(settings),
                new IndexNameModule(index),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(PercolatorExecutor.class).asEagerSingleton();
                        bind(ClusterService.class).toProvider(Providers.of((ClusterService) null));
                    }
                }
        ).createInjector();

        percolatorExecutor = injector.getInstance(PercolatorExecutor.class);
    }

    @AfterClass
    public void close() {
        injector.getInstance(ThreadPool.class).shutdownNow();
    }

    @Test
    public void testSimplePercolator() throws Exception {
        // introduce the doc
        XContentBuilder doc = XContentFactory.jsonBuilder().startObject().startObject("doc")
                .field("field1", 1)
                .field("field2", "value")
                .endObject().endObject();
        BytesReference source = doc.bytes();

        XContentBuilder docWithType = XContentFactory.jsonBuilder().startObject().startObject("doc").startObject("type1")
                .field("field1", 1)
                .field("field2", "value")
                .endObject().endObject().endObject();
        BytesReference sourceWithType = docWithType.bytes();

        percolatorExecutor.clearQueries(); // remove all previously added queries
        PercolatorExecutor.Response percolate = percolatorExecutor.percolate(new PercolatorExecutor.SourceRequest("type1", source));
        assertThat(percolate.matches(), hasSize(0));

        // add a query
        percolatorExecutor.addQuery("test1", termQuery("field2", "value"));

        percolate = percolatorExecutor.percolate(new PercolatorExecutor.SourceRequest("type1", source));
        assertThat(percolate.matches(), hasSize(1));
        assertThat(percolate.matches(), hasItem("test1"));

        percolate = percolatorExecutor.percolate(new PercolatorExecutor.SourceRequest("type1", sourceWithType));
        assertThat(percolate.matches(), hasSize(1));
        assertThat(percolate.matches(), hasItem("test1"));

        percolatorExecutor.addQuery("test2", termQuery("field1", 1));

        percolate = percolatorExecutor.percolate(new PercolatorExecutor.SourceRequest("type1", source));
        assertThat(percolate.matches(), hasSize(2));
        assertThat(percolate.matches(), hasItems("test1", "test2"));


        percolatorExecutor.removeQuery("test2");
        percolate = percolatorExecutor.percolate(new PercolatorExecutor.SourceRequest("type1", source));
        assertThat(percolate.matches(), hasSize(1));
        assertThat(percolate.matches(), hasItems("test1"));

        // add a range query (cached)
        // add a query
        percolatorExecutor.addQuery("test1", constantScoreQuery(FilterBuilders.rangeFilter("field2").from("value").includeLower(true)));

        percolate = percolatorExecutor.percolate(new PercolatorExecutor.SourceRequest("type1", source));
        assertThat(percolate.matches(), hasSize(1));
        assertThat(percolate.matches(), hasItem("test1"));
    }
    
    @Test
    public void testConcurrentPerculator() throws InterruptedException, IOException {
        // introduce the doc
        XContentBuilder bothQueriesB = XContentFactory.jsonBuilder().startObject().startObject("doc")
                .field("field1", 1)
                .field("field2", "value")
                .endObject().endObject();
        final BytesReference bothQueries = bothQueriesB.bytes();

        XContentBuilder onlyTest1B = XContentFactory.jsonBuilder().startObject().startObject("doc")
                .field("field2", "value")
                .endObject().endObject();
        XContentBuilder onlyTest2B = XContentFactory.jsonBuilder().startObject().startObject("doc")
        .field("field1", 1)
        .endObject().endObject();
        final BytesReference onlyTest1 = onlyTest1B.bytes();
        final BytesReference onlyTest2 = onlyTest2B.bytes();
        final PercolatorExecutor executor = this.percolatorExecutor;
        percolatorExecutor.clearQueries(); // remove all previously added queries
        // this adds the mapping and ensures that we do a NRQ for field 1
        PercolatorExecutor.Response percolate = percolatorExecutor.percolate(new PercolatorExecutor.SourceRequest("type1", bothQueries));
        assertThat(percolate.matches(), hasSize(0));
        executor.addQuery("test1", termQuery("field2", "value"));
        executor.addQuery("test2", termQuery("field1", 1));
        
        final IndexSettingsService settingsService = injector.getInstance(IndexSettingsService.class);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicInteger counts = new AtomicInteger(0);
        Thread[] threads = new Thread[5];
        
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread() {
              public void run() {
                  try {

                 
                  start.await();
               
                  PercolatorExecutor.Response percolate;
                  while(!stop.get()) {
                      int count = counts.incrementAndGet();
                      if ((count % 100) == 0) {
                          ImmutableSettings.Builder builder = ImmutableSettings.settingsBuilder();
                          builder.put(PercolatorExecutor.PERCOLATE_POOL_MAX_MEMORY, 1 + (counts.get() % 10), ByteSizeUnit.MB);
                          builder.put(PercolatorExecutor.PERCOLATE_POOL_SIZE, 1 + (counts.get() % 10));
                          builder.put(PercolatorExecutor.PERCOLATE_TIMEOUT, 1 + (counts.get() % 1000), TimeUnit.MILLISECONDS);
                          settingsService.refreshSettings(builder.build());
                      }
                      
                      if ((count > 10000)) {
                          stop.set(true);
                      }
                      if (count % 3 == 0) {
                          percolate = executor.percolate(new PercolatorExecutor.SourceRequest("type1", bothQueries));
                          assertThat(percolate.matches(), hasSize(2));
                          assertThat(percolate.matches(), hasItems("test1", "test2"));
                      } else if (count % 3 == 1) {
                          percolate = executor.percolate(new PercolatorExecutor.SourceRequest("type1", onlyTest1));
                          assertThat(percolate.matches(), hasSize(1));
                          assertThat(percolate.matches(), hasItems("test1"));    
                      }  else {
                          percolate = executor.percolate(new PercolatorExecutor.SourceRequest("type1", onlyTest2));
                          assertThat(percolate.matches(), hasSize(1));
                          assertThat(percolate.matches(), hasItems("test2"));    
                      }

                      
                  }
                  
                  } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                  }
              }
            };
            threads[i].start();
        }
        
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        
    }
}
