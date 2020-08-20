package bb;

import net.bytebuddy.build.Plugin;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.ChangeType;
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

public abstract class TransformationTask extends DefaultTask {

    @Incremental
    @InputDirectory
    public abstract DirectoryProperty getSource();

    @OutputDirectory
    public abstract DirectoryProperty getTarget();

    @TaskAction
    public void transform(InputChanges changes) throws IOException {
        if (getSource().getAsFile().get().equals(getTarget().getAsFile().get())) {
            throw new GradleException("Source and target folder cannot be equal: " + getSource().getAsFile().get());
        }
        Plugin.Engine.Source source;
        if (changes.isIncremental()) {
            Path sourceRoot = getSource().get().getAsFile().toPath(), targetRoot = getTarget().get().getAsFile().toPath();
            Set<String> roots = new HashSet<>();
            changes.getFileChanges(getSource()).forEach(change -> {
                if (change.getFile().isDirectory()) {
                    return;
                }
                String file = sourceRoot.relativize(change.getFile().toPath()).toString();
                Optional<String> baseName = toClassFileBaseName(file);
                if (baseName.isPresent()) {
                    roots.add(baseName.get());
                } else if (change.getChangeType() == ChangeType.REMOVED) {
                    try {
                        Files.deleteIfExists(targetRoot.resolve(file));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                } else {
                    try {
                        Files.copy(change.getFile().toPath(), targetRoot.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            });
            getLogger().info("Found {} roots: {}", roots.size(), roots);
            List<File> files = new ArrayList<>();
            getSource().getAsFileTree().visit(details -> {
                if (!details.isDirectory() && toClassFileBaseName(details.getPath()).filter(roots::contains).isPresent()) {
                    files.add(details.getFile());
                }
            });
            Set<String> names = files.stream().map(File::toString).collect(Collectors.toSet());
            getTarget().getAsFileTree().visit(details -> {
                if (!details.isDirectory() && toClassFileBaseName(details.getPath()).filter(names::contains).isPresent()) {
                    if (!getProject().delete(details.getFile())) {
                        throw new GradleException("Cannot delete file " + details.getPath() + " in target folder");
                    }
                }
            });
            source = new ExplicitFileSource(getSource().getAsFile().get(), files);
        } else {
            getProject().delete(getTarget().getAsFileTree());
            source = new Plugin.Engine.Source.ForFolder(getSource().getAsFile().get());
        }
        Plugin.Engine.Summary summary = new Plugin.Engine.Default().apply(
                source,
                new Plugin.Engine.Target.ForFolder(getTarget().getAsFile().get()),
                DummyPlugin::new
        );
        getLogger().info("Transformed {} classes (incremental = {})", summary.getTransformed().size(), changes.isIncremental());
    }

    private static Optional<String> toClassFileBaseName(String name) {
        if (name.endsWith(".class")) {
            return Optional.of(name.substring(0, name.contains("$")
                    ? name.indexOf('$')
                    : name.length() - ".class".length()));
        } else {
            return Optional.empty();
        }
    }
}
