package hudson.plugins.throttleconcurrents.pipeline;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.Serializable;

public class ThrottledStepInfo implements Serializable {
    private String category;

    private String node;

    private ThrottledStepInfo parentInfo;

    public ThrottledStepInfo(@Nonnull String category) {
        this.category = category;
    }

    @Nonnull
    public String getCategory() {
        return category;
    }

    public void setNode(@Nonnull String node) {
        this.node = node;
    }

    @CheckForNull
    public String getNode() {
        return node;
    }

    public void setParentInfo(@Nonnull ThrottledStepInfo parentInfo) {
        this.parentInfo = parentInfo;
    }

    @CheckForNull
    public ThrottledStepInfo getParentInfo() {
        return parentInfo;
    }

    public ThrottledStepInfo forCategory(@Nonnull String cat) {
        if (cat.equals(category)) {
            return this;
        } else if (getParentInfo() != null) {
            return getParentInfo().forCategory(cat);
        } else {
            return null;
        }
    }

    private static final long serialVersionUID = 1L;
}
