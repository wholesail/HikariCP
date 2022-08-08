/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.hikari.pool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.metrics.dropwizard.CodahaleMetricsTrackerFactory;
import com.zaxxer.hikari.util.UtilityElf;
import org.junit.Test;

import static com.zaxxer.hikari.pool.TestElf.newHikariConfig;
import static com.zaxxer.hikari.pool.TestElf.newHikariDataSource;
import static com.zaxxer.hikari.util.UtilityElf.quietlySleep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

/**
 * Test HikariCP/CodaHale metrics integration.
 *
 * @author Brett Wooldridge
 */
public class CodahaleMetricsTest extends TestMetricsBase<MetricRegistry>
{
   @Override
   protected MetricsTrackerFactory metricsTrackerFactory(final MetricRegistry metricRegistry)
   {
      return new CodahaleMetricsTrackerFactory(metricRegistry);
   }

   @Override
   protected MetricRegistry metricRegistry()
   {
      return new MetricRegistry();
   }

   @Test
   public void testHealthChecks() throws Exception
   {
      MetricRegistry metricRegistry = metricRegistry();
      HealthCheckRegistry healthRegistry = new HealthCheckRegistry();

      HikariConfig config = newHikariConfig();
      config.setMaximumPoolSize(10);
      config.setMetricRegistry(metricRegistry);
      config.setHealthCheckRegistry(healthRegistry);
      config.setDataSourceClassName("com.zaxxer.hikari.mocks.StubDataSource");
      config.addHealthCheckProperty("connectivityCheckTimeoutMs", "1000");
      config.addHealthCheckProperty("expected99thPercentileMs", "100");

      try (HikariDataSource ds = new HikariDataSource(config)) {
         quietlySleep(TimeUnit.SECONDS.toMillis(2));

         try (Connection connection = ds.getConnection()) {
            // close immediately
         }

         try (Connection connection = ds.getConnection()) {
            // close immediately
         }

         SortedMap<String, HealthCheck.Result> healthChecks = healthRegistry.runHealthChecks();

         HealthCheck.Result connectivityResult = healthChecks.get("testHealthChecks.pool.ConnectivityCheck");
         assertTrue(connectivityResult.isHealthy());

         HealthCheck.Result slaResult = healthChecks.get("testHealthChecks.pool.Connection99Percent");
         assertTrue(slaResult.isHealthy());
      }
   }

   @Test
   public void testSetters1() throws Exception
   {
      try (HikariDataSource ds = newHikariDataSource()) {
         ds.setMaximumPoolSize(1);
         ds.setDataSourceClassName("com.zaxxer.hikari.mocks.StubDataSource");

         MetricRegistry metricRegistry = metricRegistry();
         HealthCheckRegistry healthRegistry = new HealthCheckRegistry();

         try {
            try (Connection connection = ds.getConnection()) {
               // close immediately
            }

            // After the pool as started, we can only set them once...
            ds.setMetricRegistry(metricRegistry);
            ds.setHealthCheckRegistry(healthRegistry);

            // and never again...
            ds.setMetricRegistry(metricRegistry);
            fail("Should not have been allowed to set registry after pool started");
         }
         catch (IllegalStateException ise) {
            // pass
            try {
               ds.setHealthCheckRegistry(healthRegistry);
               fail("Should not have been allowed to set registry after pool started");
            }
            catch (IllegalStateException ise2) {
               // pass
            }
         }
      }
   }

   @Test
   public void testSetters2() throws Exception
   {
      try (HikariDataSource ds = newHikariDataSource()) {
         ds.setMaximumPoolSize(1);
         ds.setDataSourceClassName("com.zaxxer.hikari.mocks.StubDataSource");

         MetricRegistry metricRegistry = metricRegistry();
         HealthCheckRegistry healthRegistry = new HealthCheckRegistry();

         ds.setMetricRegistry(metricRegistry);
         ds.setHealthCheckRegistry(healthRegistry);

         // before the pool is started, we can set it any number of times...
         ds.setMetricRegistry(metricRegistry);
         ds.setHealthCheckRegistry(healthRegistry);

         try (Connection connection = ds.getConnection()) {

            // after the pool is started, we cannot set it any more
            ds.setMetricRegistry(metricRegistry);
            fail("Should not have been allowed to set registry after pool started");
         }
         catch (IllegalStateException ise) {
            // pass
         }
      }
   }

   @Test
   public void testMetricWait() throws SQLException
   {
      MetricRegistry metricRegistry = new MetricRegistry();

      HikariConfig config = newHikariConfig();
      config.setMinimumIdle(1);
      config.setMaximumPoolSize(1);
      config.setMetricRegistry(metricRegistry);
      config.setInitializationFailTimeout(Long.MAX_VALUE);
      config.setDataSourceClassName("com.zaxxer.hikari.mocks.StubDataSource");

      try (HikariDataSource ds = new HikariDataSource(config)) {
         ds.getConnection().close();

         Timer timer = metricRegistry.getTimers(new MetricFilter() {
            /** {@inheritDoc} */
            @Override
            public boolean matches(String name, Metric metric)
            {
               return name.equals(MetricRegistry.name("testMetricWait", "pool", "Wait"));
            }
         }).values().iterator().next();

         assertEquals(1, timer.getCount());
         assertTrue(timer.getMeanRate() > 0.0);
      }
   }

   @Test
   public void testMetricUsage() throws SQLException
   {
      assumeFalse(System.getProperty("os.name").contains("Windows"));
      MetricRegistry metricRegistry = new MetricRegistry();

      HikariConfig config = newHikariConfig();
      config.setMinimumIdle(1);
      config.setMaximumPoolSize(1);
      config.setMetricRegistry(metricRegistry);
      config.setInitializationFailTimeout(0);
      config.setDataSourceClassName("com.zaxxer.hikari.mocks.StubDataSource");

      try (HikariDataSource ds = new HikariDataSource(config)) {
         try (Connection connection = ds.getConnection()) {
            UtilityElf.quietlySleep(250L);
         }

         Histogram histo = metricRegistry.getHistograms(new MetricFilter() {
            /** {@inheritDoc} */
            @Override
            public boolean matches(String name, Metric metric)
            {
               return name.equals(MetricRegistry.name("testMetricUsage", "pool", "Usage"));
            }
         }).values().iterator().next();

         assertEquals(1, histo.getCount());
         double seventyFifth = histo.getSnapshot().get75thPercentile();
         assertTrue("Seventy-fith percentile less than 250ms: " + seventyFifth, seventyFifth >= 250.0);
      }
   }

   @Test
   public void testMetricRegistrySubclassIsAllowed()
   {
      try (HikariDataSource ds = newHikariDataSource()) {
         ds.setMaximumPoolSize(1);
         ds.setDataSourceClassName("com.zaxxer.hikari.mocks.StubDataSource");

         ds.setMetricRegistry(new MetricRegistry() {
            @Override
            public Timer timer(String name) {
               return super.timer(name);
            }
         });
      }
   }
}
