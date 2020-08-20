package foo;

public class Qux {

    private final Bar bar;

    public Qux(Bar bar) {
        this.bar = bar;
    }

    public void method1() {
        bar.method();
    }
}
