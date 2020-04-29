package io.alicorn.v8;

import com.eclipsesource.v8.*;

import java.util.*;

/**
 * Utilities for adapting Java classes and objects into a V8 runtime.
 *
 * @author Brandon Sanders [brandon@alicorn.io]
 */
public final class V8JavaAdapter {

    // Mapping of V8 instances to their associated caches.
    private static final Map<V8, V8JavaCache> runtimeToCacheMap = new WeakHashMap<V8, V8JavaCache>();

    /**
     * Returns the {@link V8JavaCache} associated with a given runtime.
     *
     * If a cache for that runtime does not exist, one will be created when this method is invoked.
     *
     * @param v8 V8 runtime to retrieve the cache for.
     *
     * @return The {@link V8JavaCache} associated with the given runtime.
     */
    public static V8JavaCache getCacheForRuntime(V8 v8) {
        if (runtimeToCacheMap.containsKey(v8)) {
            return runtimeToCacheMap.get(v8);
        } else {
            V8JavaCache cache = new V8JavaCache();
            runtimeToCacheMap.put(v8, cache);
            return cache;
        }
    }

    /**
     * Injects an existing Java object into V8 as a variable.
     *
     * If the passed object represents a primitive array (e.g., String[], Object[], int[]),
     * the array will be unwrapped and injected into the V8 context as an ArrayList. Any
     * modifications made to the injected list will not be passed back up to the Java runtime.
     *
     * This method will immediately invoke {@link #injectClass(String, Class, V8Object)}
     * before injecting the object, causing the object's class to be automatically
     * injected into the V8 Object if it wasn't already.
     *
     * <b>NOTE: </b> If you wish to use an interceptor for the class of an injected object,
     * you must explicitly invoke {@link #injectClass(Class, V8JavaClassInterceptor, V8Object)} or
     * {@link #injectClass(String, Class, V8JavaClassInterceptor, V8Object)}. This method will
     * <b>NOT</b> specify an interceptor automatically for the injected object.
     *
     * @param name Name of the variable to assign the Java object to. If this value is null,
     *             a UUID will be automatically generated and used as the name of the variable.
     * @param object Java object to inject.
     * @param rootObject {@link V8Object} to inject the Java object into.
     *
     * @return String identifier of the injected object.
     */
    public static String injectObject(String name, Object object, V8Object rootObject) {

        // Determine cache to use.
        V8JavaCache cache = getCacheForRuntime(rootObject.getRuntime());

        //TODO: Add special handlers for N-dimensional and primitive arrays.
        //TODO: This should inject arrays as JS arrays, not lists. Meh.
        //TODO: This will bypass interceptors in some cases.
        //TODO: This is terrible.
        if (object.getClass().isArray()) {
            Object[] rawArray = (Object[]) object;
            List<Object> injectedArray = new ArrayList<Object>(rawArray.length);
            for (Object obj : rawArray) {
                injectedArray.add(obj);
            }
            return injectObject(name, injectedArray, rootObject);
        } else {
            injectClass("".equals(object.getClass().getSimpleName()) ?
                                object.getClass().getName().replaceAll("\\.+", "_") :
                                object.getClass().getSimpleName(),
                        object.getClass(),
                        rootObject);
        }

        if (name == null) {
            name = "TEMP" + UUID.randomUUID().toString().replaceAll("-", "");
        }
        final V8 v8 = V8JavaObjectUtils.getRuntimeSarcastically(rootObject);

        //Build an empty object instance.
        V8JavaClassProxy proxy = cache.cachedV8JavaClasses.get(object.getClass());
        StringBuilder script = new StringBuilder();
        script.append("var ").append(name).append(" = new function() {");

        boolean objectInjectionOverridden = false;
        // Attach interceptor.
        if (proxy.getInterceptor() != null) {
            //override injection if any
            final Object injectionOverride = proxy.getInterceptor().objectInjectorOverride(object);
            objectInjectionOverridden = injectionOverride != null;
            if (!objectInjectionOverridden) {
                script.append(proxy.getInterceptor().getConstructorScriptBody());
            } else {
                final V8Value convertedToV8JavaObject;
                if (injectionOverride instanceof V8Object) {
                    convertedToV8JavaObject = (V8Value) injectionOverride;
                } else {
                    convertedToV8JavaObject = (V8Value) V8JavaObjectUtils.translateJavaArgumentToJavascript(injectionOverride, v8, cache);
                }

                String javaObjectName = name + "java";
                v8.add(javaObjectName, convertedToV8JavaObject);
                convertedToV8JavaObject.release();

                //return injected javaObject and clear reference to this variable
                script.append("try { return ").append(javaObjectName).append("; } finally { " + javaObjectName + " = undefined; }");
            }

        }

        script.append("\n}; ").append(name).append(";");

        V8Object other = v8.executeObjectScript(script.toString());

        String id;
        if (!objectInjectionOverridden) {
            id = proxy.attachJavaObjectToJsObject(object, other);
        } else {
            /**
             * There is no need to attach v8 object, which is result of overridden java object injection:
             *  - it's either completely self-contained new V8 object
             *  - or if it's normal java object - it's already attached in .translateJavaArgumentToJavascript() method.
             */
            id = name;
        }

        other.release();

        return id;
    }

    /**
     * Injects a Java class into a V8 object as a prototype.
     *
     * The injected "class" will be equivalent to a Java Script prototype with
     * a name identical to the one specified when invoking this function. For
     * example, the java class {@code com.foo.Bar} could be new'd from the Java Script
     * context by invoking {@code new Bar()} if {@code "Bar"} was passed as the
     * name use when injecting the class.
     *
     * @param name Name to use when injecting the class into the V8 object.
     * @param classy Java class to inject.
     * @param interceptor {@link V8JavaClassInterceptor} to use with this class. Pass null if no interceptor is desired.
     * @param rootObject {@link V8Object} to inject the Java class into.
     */
    public static void injectClass(String name, Class<?> classy, V8JavaClassInterceptor interceptor, V8Object rootObject) {
        //Calculate V8-friendly full class names.
        String v8FriendlyClassname = classy.getName().replaceAll("\\.+", "_");

        // Determine cache to use.
        V8JavaCache cache = getCacheForRuntime(rootObject.getRuntime());

        //Register the class proxy.
        V8JavaClassProxy proxy;
        if (cache.cachedV8JavaClasses.containsKey(classy)) {
            proxy = cache.cachedV8JavaClasses.get(classy);
        } else {
            proxy = new V8JavaClassProxy(classy, interceptor, cache);
            cache.cachedV8JavaClasses.put(classy, proxy);
        }

        //Check if the root object already has a constructor.
        //TODO: Is this faster or slower than checking if a specific V8Value is "undefined"?
        if (!Arrays.asList(rootObject.getKeys()).contains(name)) {
            rootObject.registerJavaMethod(proxy, name);
           /* rootObject.registerJavaMethod(proxy, "v8ConstructJavaClass" + v8FriendlyClassname);
            //Build up the constructor script.
            StringBuilder script = new StringBuilder();
            script.append("this.").append(name).append(" = function() {");
            script.append("v8ConstructJavaClass").append(v8FriendlyClassname).append(".apply(this, arguments);");

            // Attach interceptor.
            if (proxy.getInterceptor() != null) {
                script.append(proxy.getInterceptor().getConstructorScriptBody());
            }

            script.append("\n};");

            //Evaluate the script to create a new constructor function.
            V8JavaObjectUtils.getRuntimeSarcastically(rootObject).executeVoidScript(script.toString());
*/
            //Build up static methods if needed.
            if (proxy.getInterceptor() == null) {
                V8Object constructorFunction = (V8Object) rootObject.get(name);
                for (V8JavaStaticMethodProxy method : proxy.getStaticMethods()) {
                    constructorFunction.registerJavaMethod(method, method.getMethodName());
                }

                //Clean up after ourselves.
                constructorFunction.release();
            }
        }
    }

    public static void injectClass(Class<?> classy, V8JavaClassInterceptor interceptor, V8Object object) {
        injectClass(classy.getSimpleName(), classy, interceptor, object);
    }

    public static void injectClass(String name, Class<?> classy, V8Object object) {
        injectClass(name, classy, null, object);
    }

    public static void injectClass(Class<?> classy, V8Object object) {
        injectClass(classy.getSimpleName(), classy, null, object);
    }
}