package org.robolectric.internal.bytecode;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SwitchPoint;
import org.robolectric.internal.ShadowedObject;

import static java.lang.invoke.MethodHandles.catchException;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.exactInvoker;
import static java.lang.invoke.MethodHandles.filterArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.throwException;
import static java.lang.invoke.MethodType.methodType;
import static org.robolectric.internal.bytecode.MethodCallSite.Kind.REGULAR;
import static org.robolectric.internal.bytecode.MethodCallSite.Kind.STATIC;

public class InvokeDynamicSupport {
  private static final MethodHandle BIND_CALL_SITE;
  private static final MethodHandle BIND_INIT_CALL_SITE;
  private static final MethodHandle EXCEPTION_HANDLER;
  private static final MethodHandle GET_SHADOW;

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();

      BIND_CALL_SITE = lookup.findStatic(InvokeDynamicSupport.class, "bindCallSite",
          methodType(MethodHandle.class, MethodCallSite.class));
      BIND_INIT_CALL_SITE = lookup.findStatic(InvokeDynamicSupport.class, "bindInitCallSite",
          methodType(MethodHandle.class, RoboCallSite.class));
      MethodHandle cleanStackTrace = lookup.findStatic(RobolectricInternals.class, "cleanStackTrace",
          methodType(Throwable.class, Throwable.class));
      EXCEPTION_HANDLER = filterArguments(throwException(void.class, Throwable.class), 0, cleanStackTrace);
      GET_SHADOW = lookup.findVirtual(ShadowedObject.class, "$$robo$getData", methodType(Object.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  @SuppressWarnings("UnusedDeclaration")
  public static CallSite bootstrapInit(MethodHandles.Lookup caller, String name, MethodType type) {
    RoboCallSite site = new RoboCallSite(type, caller.lookupClass());

    bindInitCallSite(site);

    return site;
  }

  @SuppressWarnings("UnusedDeclaration")
  public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type,
      MethodHandle original) throws IllegalAccessException {
    MethodCallSite site = new MethodCallSite(type, caller.lookupClass(), name, original, REGULAR);

    bindCallSite(site);

    return site;
  }

  @SuppressWarnings("UnusedDeclaration")
  public static CallSite bootstrapStatic(MethodHandles.Lookup caller, String name, MethodType type,
      MethodHandle original) throws IllegalAccessException {
    MethodCallSite site = new MethodCallSite(type, caller.lookupClass(), name, original, STATIC);

    bindCallSite(site);

    return site;
  }

  @SuppressWarnings("UnusedDeclaration")
  public static CallSite bootstrapIntrinsic(MethodHandles.Lookup caller, String name,
      MethodType type, String callee) throws IllegalAccessException {

    MethodHandle mh = new AndroidInterceptors().build().getMethodHandle(callee, name, type);
    if (mh == null) {
      throw new IllegalArgumentException("Could not find intrinsic for " + callee + ":" + name);
    }

    return new ConstantCallSite(mh.asType(type));
  }

  private static MethodHandle bindInitCallSite(RoboCallSite site) {
    MethodHandle mh = RobolectricInternals.getShadowCreator(site.getCaller());
    return bindWithFallback(mh, site, BIND_INIT_CALL_SITE);
  }

  private static MethodHandle bindCallSite(MethodCallSite site) throws IllegalAccessException {
    MethodHandle mh =
        RobolectricInternals.findShadowMethod(site.getCaller(), site.getName(), site.type(),
            site.isStatic());

    if (mh == null) {
      // Call original code and make sure to clean stack traces
      mh = cleanStackTraces(site.getOriginal());
    } else if (mh == ShadowWrangler.DO_NOTHING) {
      mh = dropArguments(mh, 0, site.type().parameterList());
    } else if (!site.isStatic()) {
      Class<?> shadowType = mh.type().parameterType(0);
      mh = filterArguments(mh, 0, GET_SHADOW.asType(methodType(shadowType, site.thisType())));
    }

    try {
      return bindWithFallback(mh, site, BIND_CALL_SITE);
    } catch (Throwable t) {
      // The error that bubbles up is currently not very helpful so we print any error messages
      // here
      t.printStackTrace();
      System.err.println(site.getCaller());
      throw t;
    }
  }

  private static MethodHandle bindWithFallback(MethodHandle mh, RoboCallSite site, MethodHandle fallback) {
    SwitchPoint switchPoint = getInvalidator(site.getCaller());
    MethodType type = site.type();

    MethodHandle boundFallback = foldArguments(exactInvoker(type), fallback.bindTo(site));
    mh = switchPoint.guardWithTest(mh.asType(type), boundFallback);

    site.setTarget(mh);
    return mh;
  }

  private static SwitchPoint getInvalidator(Class<?> cl) {
    return RobolectricInternals.getShadowInvalidator().getSwitchPoint(cl);
  }

  private static MethodHandle cleanStackTraces(MethodHandle mh) {
    MethodType type = EXCEPTION_HANDLER.type().changeReturnType(mh.type().returnType());
    return catchException(mh, Throwable.class, EXCEPTION_HANDLER.asType(type));
  }
}