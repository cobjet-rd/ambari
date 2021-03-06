/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.metrics2.sink.timeline.cache;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@InterfaceAudience.Public
@InterfaceStability.Evolving
public class TimelineMetricsCache {

  private final TimelineMetricHolder timelineMetricCache = new TimelineMetricHolder();
  private static final Log LOG = LogFactory.getLog(TimelineMetric.class);
  public static final int MAX_RECS_PER_NAME_DEFAULT = 10000;
  public static final int MAX_EVICTION_TIME_MILLIS = 59000; // ~ 1 min
  private final int maxRecsPerName;
  private final int maxEvictionTimeInMillis;

  public TimelineMetricsCache(int maxRecsPerName, int maxEvictionTimeInMillis) {
    this.maxRecsPerName = maxRecsPerName;
    this.maxEvictionTimeInMillis = maxEvictionTimeInMillis;
  }

  class TimelineMetricWrapper {
    private long timeDiff = -1;
    private long oldestTimestamp = -1;
    private TimelineMetric timelineMetric;

    TimelineMetricWrapper(TimelineMetric timelineMetric) {
      this.timelineMetric = timelineMetric;
      this.oldestTimestamp = timelineMetric.getStartTime();
    }

    private void updateTimeDiff(long timestamp) {
      if (oldestTimestamp != -1 && timestamp > oldestTimestamp) {
        timeDiff = timestamp - oldestTimestamp;
      } else {
        oldestTimestamp = timestamp;
      }
    }

    public void putMetric(TimelineMetric metric) {
      this.timelineMetric.addMetricValues(metric.getMetricValues());
      updateTimeDiff(metric.getStartTime());
    }

    public long getTimeDiff() {
      return timeDiff;
    }

    public TimelineMetric getTimelineMetric() {
      return timelineMetric;
    }
  }

  // TODO: Change to ConcurentHashMap with weighted eviction
  class TimelineMetricHolder extends LinkedHashMap<String, TimelineMetricWrapper> {
    private static final long serialVersionUID = 1L;
    private boolean gotOverflow = false;
    // To avoid duplication at the end of the buffer and beginning of the next
    // segment of values
    private Map<String, Long> endOfBufferTimestamps = new HashMap<String, Long>();

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, TimelineMetricWrapper> eldest) {
      boolean overflow = size() > maxRecsPerName;
      if (overflow && !gotOverflow) {
        LOG.warn("Metrics cache overflow at "+ size() +" for "+ eldest);
        gotOverflow = true;
      }
      return overflow;
    }

    public TimelineMetric evict(String metricName) {
      TimelineMetricWrapper metricWrapper = this.get(metricName);

      if (metricWrapper == null
        || metricWrapper.getTimeDiff() < getMaxEvictionTimeInMillis()) {
        return null;
      }

      TimelineMetric timelineMetric = metricWrapper.getTimelineMetric();
      this.remove(metricName);

      return timelineMetric;
    }

    public void put(String metricName, TimelineMetric timelineMetric) {
      if (isDuplicate(timelineMetric)) {
        return;
      }
      TimelineMetricWrapper metric = this.get(metricName);
      if (metric == null) {
        this.put(metricName, new TimelineMetricWrapper(timelineMetric));
      } else {
        metric.putMetric(timelineMetric);
      }
      // Buffer last ts value
      endOfBufferTimestamps.put(metricName, timelineMetric.getStartTime());
    }

    /**
     * Test whether last buffered timestamp is same as the newly received.
     * @param timelineMetric @TimelineMetric
     * @return true/false
     */
    private boolean isDuplicate(TimelineMetric timelineMetric) {
      return endOfBufferTimestamps.containsKey(timelineMetric.getMetricName())
        && endOfBufferTimestamps.get(timelineMetric.getMetricName()).equals(timelineMetric.getStartTime());
    }
  }

  public TimelineMetric getTimelineMetric(String metricName) {
    if (timelineMetricCache.containsKey(metricName)) {
      return timelineMetricCache.evict(metricName);
    }

    return null;
  }

  /**
   * Getter method to help testing eviction
   * @return @int
   */
  public int getMaxEvictionTimeInMillis() {
    return maxEvictionTimeInMillis;
  }

  public void putTimelineMetric(TimelineMetric timelineMetric) {
    timelineMetricCache.put(timelineMetric.getMetricName(), timelineMetric);
  }
}
