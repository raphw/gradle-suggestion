package net.bytebuddy.build.gradle;

import org.gradle.api.Project;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public interface IncrementalResolver {

    List<File> apply(Project project, Iterable<FileChange> changes, File sourceRoot, File targetRoot);

    enum ForChangedFiles implements IncrementalResolver {

        INSTANCE;

        @Override
        public List<File> apply(Project project, Iterable<FileChange> changes, File sourceRoot, File targetRoot) {
            List<File> files = new ArrayList<>();
            for (FileChange change : changes) {
                files.add(change.getFile());
                if (!change.getFile().isDirectory() && change.getChangeType() == ChangeType.REMOVED) {
                    File target = new File(targetRoot, sourceRoot.toURI().relativize(change.getFile().toURI()).getPath());
                    if (project.delete(target)) {
                        project.getLogger().debug("Deleted removed file {} to prepare incremental build", target);
                    }
                }
            }
            return files;
        }
    }
}
