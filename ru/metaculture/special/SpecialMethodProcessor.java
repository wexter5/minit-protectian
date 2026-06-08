package ru.metaculture.special;

import ru.metaculture.MethodContext;

public interface SpecialMethodProcessor {
    String preProcess(MethodContext context);
    void postProcess(MethodContext context);
}

