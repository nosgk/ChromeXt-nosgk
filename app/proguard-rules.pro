# Xposed/Vector instantiates the entry point reflectively. Keeping only the class
# does not preserve its no-argument constructor with current R8 versions.
-keep class org.matrix.chromext.MainHook {
    public <init>();
}
-keep class org.matrix.chromext.ui.MainActivity { *; }
-keep class org.matrix.chromext.ui.InstallScriptActivity { *; }
-keep class org.matrix.chromext.provider.ScriptContentProvider { *; }
-keepattributes SourceFile,LineNumberTable
