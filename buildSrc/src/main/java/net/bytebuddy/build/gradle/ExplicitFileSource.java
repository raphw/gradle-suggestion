package net.bytebuddy.build.gradle;

import net.bytebuddy.build.Plugin;

import java.io.File;
import java.util.Iterator;
import java.util.List;

public class ExplicitFileSource extends Plugin.Engine.Source.ForFolder {

    private final File root;

    private final List<File> files;

    public ExplicitFileSource(File root, List<File> files) {
        super(root);
        this.root = root;
        this.files = files;
    }

    @Override
    public Iterator<Element> iterator() {
        return new WrappingIterator(root, files.iterator());
    }

    private static class WrappingIterator implements Iterator<Element> {

        private final File root;

        private final Iterator<File> delegate;

        public WrappingIterator(File root, Iterator<File> delegate) {
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

