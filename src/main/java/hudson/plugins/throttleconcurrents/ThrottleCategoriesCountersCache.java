/*
 * The MIT License
 *
 * Copyright 2015 CloudBees Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.throttleconcurrents;

import hudson.model.AbstractBuild;
import hudson.model.JobProperty;
import hudson.model.queue.CauseOfBlockage;
import hudson.plugins.throttleconcurrents.util.ThrottleHelper;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

/**
 * Stores additional cache for {@link ThrottleQueueTaskDispatcher}.
 * The cache is supposed to be used from {@link RunListener}.
 * @author Oleg Nenashev
 * @since TODO
 */
public class ThrottleCategoriesCountersCache {
    
    private static final ThrottleCategoriesCountersCache INSTANCE = new ThrottleCategoriesCountersCache();

    public static ThrottleCategoriesCountersCache getInstance() {
        return INSTANCE;
    }
   
    private final Map<String, CategoryEntry> categoriesMap = new HashMap<String, CategoryEntry>();
    
    private @CheckForNull Map<String, ThrottleJobProperty.ThrottleCategory> categoriesHash = null;
     
    private CategoryEntry getCategoryEntry(String categoryName) {
        CategoryEntry res = categoriesMap.get(categoryName);
        if (res == null) {
            res = new CategoryEntry(categoryName);
            categoriesMap.put(categoryName, res);
        }
        return res;
    }
    
    private Map<String, ThrottleJobProperty.ThrottleCategory>  getCategoriesHash() {
        if (categoriesHash == null) {
            categoriesHash = new HashMap<String, ThrottleJobProperty.ThrottleCategory>();
            refreshCategoriesCache();
        }
        return categoriesHash;
    }
    
    public synchronized @CheckForNull CauseOfBlockage canTake(Collection<String> categoryNames, String nodeName) {
        // Global check
        for (String categoryName : categoryNames) {
            int maxValue = getCategoriesHash().get(categoryName).getMaxConcurrentTotal();
            int totalBuildsCount = getTotalBuildsNumber(categoryName);
            if (totalBuildsCount >= maxValue) {
                return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalBuildsCount));
            }
        }
        
        // Per-node check
        for (String categoryName : categoryNames) {
            int maxValue = getCategoriesHash().get(categoryName).getMaxConcurrentPerNode();
            int totalBuildsCountOnNode = getNodeBuildsNumber(categoryName, nodeName);
            if (totalBuildsCountOnNode >= maxValue) {
                return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalBuildsCountOnNode));
            }           
        }   
        return null;
    }
    
    public synchronized @CheckForNull CauseOfBlockage canRun(Collection<String> categoryNames) {
        for (String categoryName : categoryNames) {
            int maxValue = getCategoriesHash().get(categoryName).getMaxConcurrentTotal();
            int totalBuildsCount = getTotalBuildsNumber(categoryName);
            if (totalBuildsCount >= maxValue) {
                return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalBuildsCount));
            }
        }
        return null;
    }

    /**
     * Updates global cache of category properties
     */
    public synchronized void refreshCategoriesCache() {
        Jenkins j = Jenkins.getInstance();
        if (j == null) {
            throw new IllegalStateException("Jenkins must be started");
        }
        
        ThrottleJobProperty.DescriptorImpl d = j.getDescriptorByType(ThrottleJobProperty.DescriptorImpl.class);    
        if (d == null) {
            throw new IllegalStateException("Cannot get the Throttle Property descriptor");
        }
        
        List<ThrottleJobProperty.ThrottleCategory> categories = d.getCategories();
        getCategoriesHash().clear();
        for (ThrottleJobProperty.ThrottleCategory category : categories) {
            categoriesHash.put(category.getCategoryName(), category);
        }
    }
    
    private int getTotalBuildsNumber(String categoryName) {
        return getCategoryEntry(categoryName).globalCount.count;
    }
    
    private int getNodeBuildsNumber(String categoryName, String nodeName) {
        return getCategoryEntry(categoryName).getEntry(nodeName).count;
    }
    
    //TODO: recalculate cache on jobs update
    
    public synchronized void fireStarted(AbstractBuild build) {
        JobProperty jp = ThrottleHelper.getThrottleJobProperty(build.getProject());
        if (jp == null) {
            return;
        }
        final ThrottleJobProperty tjp = (ThrottleJobProperty)jp;
        if ( !ThrottleHelper.shouldBeThrottled(build.getParent(), tjp) ) {
            return;
        }
        
        // Update the categories cache
        if (tjp.getThrottleOption().equals("category")) {
            final List<String> categories = tjp.getCategories();
            if (categories != null && !categories.isEmpty()) {
                for (String categoryName : categories) {
                    getCategoryEntry(categoryName).fireStarted(build);
                }
            }
        }
        
    }
    
    public synchronized void fireCompleted(AbstractBuild build) {      
        //TODO: remove code dup
        JobProperty jp = ThrottleHelper.getThrottleJobProperty(build.getProject());
        if (jp == null) {
            return;
        }
        final ThrottleJobProperty tjp = (ThrottleJobProperty)jp;
        if ( !ThrottleHelper.shouldBeThrottled(build.getParent(), tjp) ) {
            return;
        }
        
        // Update the categories cache
        if (tjp.getThrottleOption().equals("category")) {
            final List<String> categories = tjp.getCategories();
            if (categories != null && !categories.isEmpty()) {
                for (String categoryName : categories) {
                    getCategoryEntry(categoryName).fireCompleted(build);
                }
            }
        }
    }
    
    private static class CategoryEntry {
        private final String categoryName;
        private final CounterEntry globalCount = new CounterEntry();       
        private final Map<String, CounterEntry> nodeCounts = new HashMap<String, CounterEntry>();

        public CategoryEntry(String categoryName) {
            this.categoryName = null;
        }

        public String getCategoryName() {
            return categoryName;
        }
        
        void fireStarted(AbstractBuild build) {
            globalCount.inc();
            getEntry(build.getBuiltOnStr()).inc();
        }
        
        void fireCompleted(AbstractBuild build) {
            globalCount.dec();
            getEntry(build.getBuiltOnStr()).dec();
        }
        
        private @Nonnull CounterEntry getEntry(String nodeName) {
            String key = StringUtils.isEmpty(nodeName) ? "(master)" : nodeName;
            CounterEntry res = nodeCounts.get(key);
            if (res == null) {
                res = new CounterEntry();
                nodeCounts.put(key, res);
            }
            return res;
        }
    }
    
    private static class CounterEntry {
        int count=0;

        public int getCount() {
            return count;
        }
        
        public void inc() {
            count++;
        }
        
        public void dec() {
            count--;
        }
    }
}
