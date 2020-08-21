package net.bytebuddy.build.gradle;

import groovy.lang.Closure;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.build.BuildLogger;
import net.bytebuddy.build.EntryPoint;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.scaffold.inline.MethodNameTransformer;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.*;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class ByteBuddyTask extends DefaultTask {

    private final List<Transformation> transformations = new ArrayList<>();

    private Initialization initialization = Initialization.makeDefault();

    private String suffix = "";

    private boolean failOnLiveInitializer;

    private boolean warnOnEmptyTypeSet;

    private boolean failFast;

    private boolean extendedParsing;

    private int threads;

    private boolean incremental = true;

    @Incremental
    @InputDirectory
    public abstract DirectoryProperty getSource();

    @OutputDirectory
    public abstract DirectoryProperty getTarget();

    @InputFiles
    @CompileClasspath
    public abstract ConfigurableFileCollection getClassPath();

    @Nested
    public List<Transformation> getTransformations() {
        return transformations;
    }

    public void transformation(Closure<?> closure) {
        transformations.add((Transformation) getProject().configure(new Transformation(getProject()), closure));
    }

    @Nested
    public Initialization getInitialization() {
        return initialization;
    }

    public void initialization(Closure<?> closure) {
        if (initialization != null) {
            throw new IllegalStateException("Initialization is already set");
        }
        initialization = (Initialization) getProject().configure(new Initialization(), closure);
    }

    @Input
    public String getSuffix() {
        return suffix;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }

    @Internal
    public boolean isFailOnLiveInitializer() {
        return failOnLiveInitializer;
    }

    public void setFailOnLiveInitializer(boolean failOnLiveInitializer) {
        this.failOnLiveInitializer = failOnLiveInitializer;
    }

    @Internal
    public boolean isWarnOnEmptyTypeSet() {
        return warnOnEmptyTypeSet;
    }

    public void setWarnOnEmptyTypeSet(boolean warnOnEmptyTypeSet) {
        this.warnOnEmptyTypeSet = warnOnEmptyTypeSet;
    }

    @Internal
    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    @Input
    public boolean isExtendedParsing() {
        return extendedParsing;
    }

    public void setExtendedParsing(boolean extendedParsing) {
        this.extendedParsing = extendedParsing;
    }

    @Internal
    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    @Internal
    public boolean isIncremental() {
        return incremental;
    }

    public void setIncremental(boolean incremental) {
        this.incremental = incremental;
    }

    @TaskAction
    @SuppressWarnings("unchecked")
    public void apply(InputChanges inputChanges) throws IOException {
        File sourceRoot = getSource().get().getAsFile(), targetRoot = getTarget().get().getAsFile();
        if (sourceRoot.equals(targetRoot)) {
            throw new IllegalStateException("Source and target folder cannot be equal: " + sourceRoot);
        }
        Plugin.Engine.Source source;
        if (isIncremental() && inputChanges.isIncremental()) {
            getLogger().debug("Applying incremental build");
            List<File> files = new ArrayList<>();
            for (FileChange change : inputChanges.getFileChanges(getSource())) {
                files.add(change.getFile());
                if (change.getFile().isDirectory()) {
                    return;
                } else if (change.getChangeType() == ChangeType.REMOVED) {
                    File target = new File(targetRoot, sourceRoot.toURI().relativize(change.getFile().toURI()).getPath());
                    if (getProject().delete(target)) {
                        getLogger().debug("Deleted removed file {} to prepare incremental build", target);
                    }
                }
            }
            source = new ExplicitFileSource(getSource().getAsFile().get(), files);
        } else {
            getLogger().debug("Applying non-incremental build");
            if (getProject().delete(getTarget().getAsFileTree())) {
                getLogger().debug("Deleted all target files");
            }
            source = new Plugin.Engine.Source.ForFolder(getSource().getAsFile().get());
        }
        ClassLoaderResolver classLoaderResolver = new ClassLoaderResolver();
        try {
            List<Plugin.Factory> factories = new ArrayList<Plugin.Factory>(getTransformations().size());
            for (Transformation transformation : getTransformations()) {
                String plugin = transformation.plugin();
                try {
                    factories.add(new Plugin.Factory.UsingReflection((Class<? extends Plugin>) Class.forName(plugin,
                            false,
                            classLoaderResolver.resolve(transformation.iterate(sourceRoot, getClassPath()))))
                            .with(transformation.makeArgumentResolvers())
                            .with(Plugin.Factory.UsingReflection.ArgumentResolver.ForType.of(File.class, sourceRoot),
                                    Plugin.Factory.UsingReflection.ArgumentResolver.ForType.of(Logger.class, getLogger()),
                                    Plugin.Factory.UsingReflection.ArgumentResolver.ForType.of(BuildLogger.class, new GradleBuildLogger(getLogger()))));
                    getLogger().info("Resolved plugin: {}", transformation.getPlugin());
                } catch (Throwable throwable) {
                    throw new IllegalStateException("Cannot resolve plugin: " + transformation.getPlugin(), throwable);
                }
            }
            EntryPoint entryPoint = getInitialization().entryPoint(classLoaderResolver, sourceRoot, getClassPath());
            getLogger().info("Resolved entry point: {}", entryPoint);
            List<ClassFileLocator> classFileLocators = new ArrayList<ClassFileLocator>();
            for (File artifact : getClassPath()) {
                classFileLocators.add(artifact.isFile()
                        ? ClassFileLocator.ForJarFile.of(artifact)
                        : new ClassFileLocator.ForFolder(artifact));
            }
            ClassFileLocator classFileLocator = new ClassFileLocator.Compound(classFileLocators);
            Plugin.Engine.Summary summary;
            try {
                getLogger().info("Processing class files located in in: {}", sourceRoot);
                Plugin.Engine pluginEngine;
                try {
                    ClassFileVersion classFileVersion;
                    JavaPluginConvention convention = (JavaPluginConvention) getProject().getConvention().getPlugins().get("java");
                    if (convention == null) {
                        classFileVersion = ClassFileVersion.ofThisVm();
                        getLogger().warn("Could not locate Java target version, build is JDK dependant: {}", classFileVersion.getJavaVersion());
                    } else {
                        classFileVersion = ClassFileVersion.ofJavaVersion(Integer.parseInt(convention.getTargetCompatibility().getMajorVersion()));
                        getLogger().debug("Java version detected: {}", classFileVersion.getJavaVersion());
                    }
                    pluginEngine = Plugin.Engine.Default.of(entryPoint, classFileVersion, getSuffix().length() == 0
                            ? MethodNameTransformer.Suffixing.withRandomSuffix()
                            : new MethodNameTransformer.Suffixing(suffix));
                } catch (Throwable throwable) {
                    throw new IllegalStateException("Cannot create plugin engine", throwable);
                }
                try {
                    summary = pluginEngine
                            .with(isExtendedParsing()
                                    ? Plugin.Engine.PoolStrategy.Default.EXTENDED
                                    : Plugin.Engine.PoolStrategy.Default.FAST)
                            .with(classFileLocator)
                            .with(new TransformationLogger(getLogger()))
                            .withErrorHandlers(Plugin.Engine.ErrorHandler.Enforcing.ALL_TYPES_RESOLVED, isFailOnLiveInitializer()
                                    ? Plugin.Engine.ErrorHandler.Enforcing.NO_LIVE_INITIALIZERS
                                    : Plugin.Engine.Listener.NoOp.INSTANCE, isFailFast()
                                    ? Plugin.Engine.ErrorHandler.Failing.FAIL_FAST
                                    : Plugin.Engine.Listener.NoOp.INSTANCE)
                            .with(getThreads() == 0
                                    ? Plugin.Engine.Dispatcher.ForSerialTransformation.Factory.INSTANCE
                                    : new Plugin.Engine.Dispatcher.ForParallelTransformation.WithThrowawayExecutorService.Factory(getThreads()))
                            .apply(source, new Plugin.Engine.Target.ForFolder(targetRoot), factories);
                } catch (Throwable throwable) {
                    throw new IllegalStateException("Failed to transform class files in " + sourceRoot, throwable);
                }
            } finally {
                classFileLocator.close();
            }
            if (!summary.getFailed().isEmpty()) {
                throw new IllegalStateException(summary.getFailed() + " type transformations have failed");
            } else if (isWarnOnEmptyTypeSet() && summary.getTransformed().isEmpty()) {
                getLogger().warn("No types were transformed during plugin execution");
            } else {
                getLogger().info("Transformed {} types", summary.getTransformed().size());
            }
        } finally {
            classLoaderResolver.close();
        }
        // TODO: result type? (NO-SOURCE etc)
        // TODO: Worker API
    }

    /**
     * A {@link BuildLogger} implementation for a Gradle {@link Logger}.
     */
    protected static class GradleBuildLogger implements BuildLogger {

        /**
         * The logger to delegate to.
         */
        private final Logger logger;

        /**
         * Creates a new Gradle build logger.
         *
         * @param logger The logger to delegate to.
         */
        protected GradleBuildLogger(Logger logger) {
            this.logger = logger;
        }

        /**
         * {@inheritDoc}
         */
        public boolean isDebugEnabled() {
            return logger.isDebugEnabled();
        }

        /**
         * {@inheritDoc}
         */
        public void debug(String message) {
            logger.debug(message);
        }

        /**
         * {@inheritDoc}
         */
        public void debug(String message, Throwable throwable) {
            logger.debug(message, throwable);
        }

        /**
         * {@inheritDoc}
         */
        public boolean isInfoEnabled() {
            return logger.isInfoEnabled();
        }

        /**
         * {@inheritDoc}
         */
        public void info(String message) {
            logger.info(message);
        }

        /**
         * {@inheritDoc}
         */
        public void info(String message, Throwable throwable) {
            logger.info(message, throwable);
        }

        /**
         * {@inheritDoc}
         */
        public boolean isWarnEnabled() {
            return logger.isWarnEnabled();
        }

        /**
         * {@inheritDoc}
         */
        public void warn(String message) {
            logger.warn(message);
        }

        /**
         * {@inheritDoc}
         */
        public void warn(String message, Throwable throwable) {
            logger.warn(message, throwable);
        }

        /**
         * {@inheritDoc}
         */
        public boolean isErrorEnabled() {
            return logger.isErrorEnabled();
        }

        /**
         * {@inheritDoc}
         */
        public void error(String message) {
            logger.error(message);
        }

        /**
         * {@inheritDoc}
         */
        public void error(String message, Throwable throwable) {
            logger.error(message, throwable);
        }
    }

    /**
     * A {@link Plugin.Engine.Listener} that logs several relevant events during the build.
     */
    protected static class TransformationLogger extends Plugin.Engine.Listener.Adapter {

        /**
         * The logger to delegate to.
         */
        private final Logger logger;

        /**
         * Creates a new transformation logger.
         *
         * @param logger The logger to delegate to.
         */
        protected TransformationLogger(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void onTransformation(TypeDescription typeDescription, List<Plugin> plugins) {
            logger.debug("Transformed {} using {}", typeDescription, plugins);
        }

        @Override
        public void onError(TypeDescription typeDescription, Plugin plugin, Throwable throwable) {
            logger.warn("Failed to transform {} using {}", typeDescription, plugin, throwable);
        }

        @Override
        public void onError(Map<TypeDescription, List<Throwable>> throwables) {
            logger.warn("Failed to transform {} types", throwables.size());
        }

        @Override
        public void onError(Plugin plugin, Throwable throwable) {
            logger.error("Failed to close {}", plugin, throwable);
        }

        @Override
        public void onLiveInitializer(TypeDescription typeDescription, TypeDescription definingType) {
            logger.debug("Discovered live initializer for {} as a result of transforming {}", definingType, typeDescription);
        }
    }
}

