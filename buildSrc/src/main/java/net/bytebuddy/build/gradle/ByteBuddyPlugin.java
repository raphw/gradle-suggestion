package net.bytebuddy.build.gradle;

import org.gradle.api.NonNullApi;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

@NonNullApi
public class ByteBuddyPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        JavaPluginConvention convention = (JavaPluginConvention) project.getConvention().getPlugins().get("java");
        if (convention != null) {
            for (SourceSet set : convention.getSourceSets()) {
                project.getLogger().debug("Configuring Byte Buddy task for source set {}", project.getName());
                Provider<Directory> raw = set.getJava().getDestinationDirectory().map(directory -> directory.dir("../" + set.getName() + "Raw"));
                JavaCompile compile = (JavaCompile) project.getTasks().getByName(set.getCompileJavaTaskName());
                compile.setDestinationDir(raw.map(Directory::getAsFile));
                TaskProvider<ByteBuddyTask> byteBuddyTask = project.getTasks().register(set.getName().equals("main") ? "byteBuddy" : (set.getName() + "ByteBuddy"),
                        ByteBuddyTask.class,
                        task -> {
                            task.getSource().set(raw);
                            task.getTarget().set(set.getJava().getDestinationDirectory());
                            task.dependsOn(compile);
                        });
                for (Task task : project.getTasks()) {
                    if (task.getDependsOn().contains(compile)) {
                        task.dependsOn(byteBuddyTask);
                    }
                }
            }
        }
    }
}
