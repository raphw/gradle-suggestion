package net.bytebuddy.build.gradle;

import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

import java.io.IOException;

public class SamplePlugin implements Plugin {

    @Override
    public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassFileLocator classFileLocator) {
        return builder.defineField("proofOfWork", Void.class, Visibility.PUBLIC);
    }

    @Override
    public void close() throws IOException {

    }

    @Override
    public boolean matches(TypeDescription target) {
        return target.getSimpleName().equals("Qux") || target.getSimpleName().equals("Baz");
    }
}
