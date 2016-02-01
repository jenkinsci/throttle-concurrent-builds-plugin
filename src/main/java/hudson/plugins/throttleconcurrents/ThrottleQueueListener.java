package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;

@Extension
public class ThrottleQueueListener extends QueueListener {

    @Override
    public void onLeft(Queue.LeftItem li) {
        ThrottleNodeProperty.onTaskLeft(li);
    }

}
