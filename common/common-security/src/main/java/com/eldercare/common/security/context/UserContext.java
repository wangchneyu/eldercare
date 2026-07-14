package com.eldercare.common.security.context;

public class UserContext {
    private static final ThreadLocal<UserInfo> CONTEXT = new ThreadLocal<>();

    public static void setUser(UserInfo user) {
        CONTEXT.set(user);
    }

    public static UserInfo getUser() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
