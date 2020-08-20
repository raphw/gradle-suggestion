package bb;

import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

public class DummyPlugin implements Plugin {

    @Override
    public boolean matches(TypeDescription target) {
        System.out.println("Considering in plugin: " + target.getTypeName());
        return target.getSimpleName().equals("Qux") || target.getSimpleName().equals("Baz");
    }

    @Override
    public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder,
                                        TypeDescription typeDescription,
                                        ClassFileLocator classFileLocator) {
        System.out.println("Processing in plugin: " + typeDescription.getTypeName());
        return builder.defineField("proofOfWork", Void.class, Visibility.PUBLIC);
    }

    @Override
    public void close() { }
}
