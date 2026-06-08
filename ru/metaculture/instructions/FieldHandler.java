package ru.metaculture.instructions;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import ru.metaculture.CachedFieldInfo;
import ru.metaculture.MethodContext;
import ru.metaculture.MethodProcessor;
import ru.metaculture.Util;

/**
 * Обработчик инструкций работы с полями (GETSTATIC, PUTSTATIC, GETFIELD, PUTFIELD).
 * Генерирует Rust-код для динамического поиска классов и FieldID с использованием кэширования.
 */
public class FieldHandler extends GenericInstructionHandler<FieldInsnNode> {

    @Override
    protected void process(MethodContext context, FieldInsnNode node) {
        boolean isStatic = node.getOpcode() == Opcodes.GETSTATIC || node.getOpcode() == Opcodes.PUTSTATIC;
        CachedFieldInfo info = new CachedFieldInfo(node.owner, node.name, node.desc, isStatic);

        instructionName += "_" + Type.getType(node.desc).getSort();

        if (isStatic) {
            props.put("class_ptr", context.getCachedClasses().getPointer(node.owner));
        }

        int classId = context.getCachedClasses().getId(node.owner);
        String classPointer = context.getCachedClasses().getPointer(node.owner);
        String classInitialized = context.getCachedClasses().getInitializationCheck(node.owner);
        String classStore = context.getCachedClasses().getStoreExpression(classId, "weak_clazz");

        context.output.append(String.format(
                "{ if %s.is_null() || (**env).IsSameObject.unwrap()(env, %s, ptr::null_mut()) == JNI_TRUE { " +
                        "let _lock = CCLASSES_MTX[%d].lock().unwrap(); " +
                        "if %s.is_null() || (**env).IsSameObject.unwrap()(env, %s, ptr::null_mut()) == JNI_TRUE { " +
                        "let clazz = %s; if !clazz.is_null() { " +
                        "let weak_clazz = (**env).NewWeakGlobalRef.unwrap()(env, clazz); " +
                        "%s = weak_clazz; (**env).DeleteLocalRef.unwrap()(env, clazz); } } } %s } ",
                classInitialized, classPointer,
                classId,
                classInitialized, classPointer,
                MethodProcessor.getClassGetter(context, node.owner),
                classStore,
                trimmedTryCatchBlock
        ));

        int fieldId = context.getCachedFields().getId(info);
        props.put("fieldid", context.getCachedFields().getPointer(info));

        String fieldInitialized = context.getCachedFields().getInitializationCheck(info);
        String fieldStore = context.getCachedFields().getStoreExpression(info, "f_id");

        context.output.append(String.format(
                "if %s.is_null() { " +
                        "let f_id = (**env).Get%sFieldID.unwrap()(env, %s, %s as *const i8, %s as *const i8); " +
                        "if !f_id.is_null() { %s = f_id; } %s } ",
                fieldInitialized,
                isStatic ? "Static" : "",
                classPointer,
                context.getStringPool().get(node.name),
                context.getStringPool().get(node.desc),
                fieldStore,
                trimmedTryCatchBlock
        ));
    }

    @Override
    public String insnToString(MethodContext context, FieldInsnNode node) {
        return String.format("%s %s.%s %s", Util.getOpcodeString(node.getOpcode()), node.owner, node.name, node.desc);
    }

    /**
     * Вычисляет новое состояние указателя стека после выполнения инструкции.
     */
    @Override
    public int getNewStackPointer(FieldInsnNode node, int currentStackPointer) {
        if (node.getOpcode() == Opcodes.GETFIELD || node.getOpcode() == Opcodes.PUTFIELD) {
            currentStackPointer -= 1;
        }

        if (node.getOpcode() == Opcodes.GETSTATIC || node.getOpcode() == Opcodes.GETFIELD) {
            currentStackPointer += Type.getType(node.desc).getSize();
        }

        if (node.getOpcode() == Opcodes.PUTSTATIC || node.getOpcode() == Opcodes.PUTFIELD) {
            currentStackPointer -= Type.getType(node.desc).getSize();
        }
        return currentStackPointer;
    }
}
