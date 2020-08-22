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
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public abstract class ByteBuddyTask extends DefaultTask {

    private final List<Transformation> transformations = new ArrayList<>();

    private EntryPoint entryPoint = EntryPoint.Default.REBASE;

    private String suffix = "";

    private boolean failOnLiveInitializer;

    private boolean warnOnEmptyTypeSet;

    private boolean failFast;

    private boolean extendedParsing;

    private int threads;

    private IncrementalResolver incrementalResolver = IncrementalResolver.ForChangedFiles.INSTANCE;

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

    @Input
    public EntryPoint getEntryPoint() {
        return entryPoint;
    }

    public void setEntryPoint(EntryPoint entryPoint) {
        this.entryPoint = entryPoint;
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
    public IncrementalResolver getIncrementalResolver() {
        return incrementalResolver;
    }

    public void setIncrementalResolver(IncrementalResolver incrementalResolver) {
        this.incrementalResolver = incrementalResolver;
    }

    @TaskAction
    public void apply(InputChanges inputChanges) throws IOException {
        File sourceRoot = getSource().get().getAsFile(), targetRoot = getTarget().get().getAsFile();
        if (sourceRoot.equals(targetRoot)) {
            throw new IllegalStateException("Source and target folder cannot be equal: " + sourceRoot);
        }
        Plugin.Engine.Source source;
        if (inputChanges.isIncremental() && incrementalResolver != null) {
            getLogger().debug("Applying incremental build");
            source = new IncrementalSource(getSource().getAsFile().get(), incrementalResolver.apply(getProject(),
                    inputChanges.getFileChanges(getSource()),
                    sourceRoot,
                    targetRoot));
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
                try {
                    factories.add(new Plugin.Factory.UsingReflection(transformation.getPlugin())
                            .with(transformation.makeArgumentResolvers())
                            .with(Plugin.Factory.UsingReflection.ArgumentResolver.ForType.of(File.class, sourceRoot),
                                    Plugin.Factory.UsingReflection.ArgumentResolver.ForType.of(Logger.class, getLogger()),
                                    Plugin.Factory.UsingReflection.ArgumentResolver.ForType.of(BuildLogger.class, new GradleBuildLogger(getLogger()))));
                    getLogger().info("Resolved plugin: {}", transformation.getPlugin().getName());
                } catch (Throwable throwable) {
                    throw new IllegalStateException("Cannot resolve plugin: " + transformation.getPlugin().getName(), throwable);
                }
            }
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
                    pluginEngine = Plugin.Engine.Default.of(getEntryPoint(), classFileVersion, getSuffix().length() == 0
                            ? MethodNameTransformer.Suffixing.withRandomSuffix()
                            : new MethodNameTransformer.Suffixing(getSuffix()));
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

    protected static class IncrementalSource extends Plugin.Engine.Source.ForFolder {

        private final File root;

        private final List<File> files;

        protected IncrementalSource(File root, List<File> files) {
            super(root);
            this.root = root;
            this.files = files;
        }

        @Override
        public Iterator<Element> iterator() {
            return new DelegationIterator(root, files.iterator());
        }

        private static class DelegationIterator implements Iterator<Element> {

            private final File root;

            private final Iterator<File> delegate;

            public DelegationIterator(File root, Iterator<File> delegate) {
                this.root = root;
                this.delegate = delegate;
            }

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public Element next() {
                return new Element.ForFile(root, delegate.next());
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove");
            }
        }
    }
}

