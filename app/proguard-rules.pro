# libxposed instantiates the entry point reflectively by the name listed in
# META-INF/xposed/java_init.list. Keep the class name and its no-argument constructor.
-keep class org.matrix.chromext.MainHook {
    public <init>();
}
# The Modern API is compileOnly; R8 must not try to resolve it at shrink time.
-dontwarn io.github.libxposed.api.**
-keep class org.matrix.chromext.ui.MainActivity { *; }
-keep class org.matrix.chromext.ui.InstallScriptActivity { *; }
-keep class org.matrix.chromext.provider.ScriptContentProvider { *; }
-keepattributes SourceFile,LineNumberTable
