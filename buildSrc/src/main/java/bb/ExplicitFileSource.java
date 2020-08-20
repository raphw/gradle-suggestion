package bb;

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
        Iterator<File> iterator = files.iterator();
        return new Iterator<Element>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public Element next() {
                return new Element.ForFile(root, iterator.next());
            }
        };
    }
}
