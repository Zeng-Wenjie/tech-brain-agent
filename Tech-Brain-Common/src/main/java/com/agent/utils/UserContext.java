package com.agent.utils;
/*
 * 线程本地变量，专门用来存当前登录用户的 ID
 */
public class UserContext {

    // 线程本地变量，专门用来存当前登录用户的 ID
     public static final ThreadLocal<Long> USER_THREAD_LOCAL =new ThreadLocal<>();

     // 设置当前登录用户的 ID
     public static void setUserId(Long userId){
         USER_THREAD_LOCAL.set(userId);
     }

     // 获取当前登录用户的 ID
     public static Long getUserId(){
         return USER_THREAD_LOCAL.get();
     }

     // 删除当前登录用户的 ID
     public static void removeUserId(){
         USER_THREAD_LOCAL.remove();
     }


}
