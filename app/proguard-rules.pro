# libxposed instantiates the entry point reflectively by the name listed in
# META-INF/xposed/java_init.list, and calls the lifecycle overrides through the framework
# interface. Keep the whole class: the interface classes are compileOnly and are not in the APK,
# so R8 cannot always prove that the overrides are reachable.
-keep class org.matrix.chromext.MainHook { *; }
# The Modern API is compileOnly; R8 must not try to resolve it at shrink time.
-dontwarn io.github.libxposed.api.**
-keep class org.matrix.chromext.ui.MainActivity { *; }
-keep class org.matrix.chromext.ui.InstallScriptActivity { *; }
-keep class org.matrix.chromext.provider.ScriptContentProvider { *; }
-keepattributes SourceFile,LineNumberTable
