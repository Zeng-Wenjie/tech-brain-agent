package com.agent.utils;
/*
 * 线程本地变量，专门用来存当前登录用户的 ID
 * Thread-local holder for the current logged-in user ID.
 */
public class UserContext {

    // 线程本地变量，专门用来存当前登录用户的 ID
    // Thread-local storage for the current logged-in user ID.
     public static final ThreadLocal<Long> USER_THREAD_LOCAL =new ThreadLocal<>();

     // 设置当前登录用户的 ID
     // Set the current logged-in user ID.
     public static void setUserId(Long userId){
         USER_THREAD_LOCAL.set(userId);
     }

     // 获取当前登录用户的 ID
     // Get the current logged-in user ID.
     public static Long getUserId(){
         return USER_THREAD_LOCAL.get();
     }

     // 删除当前登录用户的 ID
     // Remove the current logged-in user ID.
     public static void removeUserId(){
         USER_THREAD_LOCAL.remove();
     }


}
