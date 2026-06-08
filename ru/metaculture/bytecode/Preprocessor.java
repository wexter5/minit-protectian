package ru.metaculture.bytecode;

import ru.metaculture.Platform;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public interface Preprocessor {

    void process(ClassNode classNode, MethodNode methodNode, Platform platform);
}

