package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;

/**
 * Created with IntelliJ IDEA.
 * User: psamoshkin
 * Date: 10/24/12
 * Time: 5:09 PM
 * To change this template use File | Settings | File Templates.
 */
public class ReleaseThrottleLockPublisher extends Publisher {

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        build.addAction(new ReleaseThrottleLockAction());
        return true;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public BuildStepDescriptor<Publisher> getDescriptor() {
        return DESCRIPTOR;
    }

    private static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        protected DescriptorImpl() {
            super(ReleaseThrottleLockPublisher.class);
        }

        @Override
        public String getDisplayName() {
            return "Release throttle-concurrent lock";
        }

        @Override
        public String getHelpFile() {
            return null;
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public Publisher newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return new ReleaseThrottleLockPublisher();
        }
    }

}
