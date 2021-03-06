/*
 * Copyright (c) 2018 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.vertx.junit5;

import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.platform.commons.util.AnnotationUtils.isAnnotated;

/**
 * JUnit 5 Vert.x extension that allows parameter injection as well as an automatic lifecycle on the {@link VertxTestContext} instance.
 * <p>
 * The following types can be injected:
 * <ul>
 * <li>{@link Vertx}</li>
 * <li>{@link VertxTestContext}</li>
 * <li>{@link io.vertx.rxjava.core.Vertx}</li>
 * <li>{@link io.vertx.reactivex.core.Vertx}</li>
 * </ul>
 *
 * @author <a href="https://julien.ponge.org/">Julien Ponge</a>
 */
public final class VertxExtension implements ParameterResolver, BeforeTestExecutionCallback, AfterTestExecutionCallback, BeforeEachCallback, AfterEachCallback, BeforeAllCallback, AfterAllCallback {

  private static final int DEFAULT_TIMEOUT_DURATION = 30;
  private static final TimeUnit DEFAULT_TIMEOUT_UNIT = TimeUnit.SECONDS;

  private final String TEST_CONTEXT_KEY = "VertxTestContext";

  private final String VERTX_INSTANCE_KEY = "VertxInstance";
  private final String VERTX_INSTANCE_CREATOR_KEY = "VertxInstanceCreator";

  private final String VERTX_RX1_INSTANCE_KEY = "VertxRx1Instance";
  private final String VERTX_RX1_INSTANCE_CREATOR_KEY = "VertxRx1InstanceCreator";

  private final String VERTX_RX2_INSTANCE_KEY = "VertxRx2Instance";
  private final String VERTX_RX2_INSTANCE_CREATOR_KEY = "VertxRx2InstanceCreator";

  private static class ContextList extends ArrayList<VertxTestContext> {
    /*
     * There may be concurrent test contexts to join at a point of time because it is allowed to have several
     * user-defined lifecycle event handles (e.g., @BeforeEach, etc).
     */
  }

  private enum CreationScope {
    BEFORE_ALL, BEFORE_EACH, TEST
  }

  private static final HashSet<Class> INJECTABLE_TYPES = new HashSet<Class>() {
    {
      add(Vertx.class);
      add(VertxTestContext.class);
      add(io.vertx.rxjava.core.Vertx.class);
      add(io.vertx.reactivex.core.Vertx.class);
    }
  };

  @Override
  public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
    return INJECTABLE_TYPES.contains(parameterType(parameterContext));
  }

  private Class<?> parameterType(ParameterContext parameterContext) {
    return parameterContext.getParameter().getType();
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
    Class<?> type = parameterType(parameterContext);
    if (type == Vertx.class) {
      return getOrCreateScopedObject(
        parameterContext,
        extensionContext,
        VERTX_INSTANCE_KEY,
        VERTX_INSTANCE_CREATOR_KEY,
        key -> new ScopedObject<>(Vertx.vertx(), closeRegularVertx()));
    }
    if (type == io.vertx.rxjava.core.Vertx.class) {
      return getOrCreateScopedObject(
        parameterContext,
        extensionContext,
        VERTX_RX1_INSTANCE_KEY,
        VERTX_RX1_INSTANCE_CREATOR_KEY,
        key -> new ScopedObject<>(io.vertx.rxjava.core.Vertx.vertx(), closeRx1Vertx()));
    }
    if (type == io.vertx.reactivex.core.Vertx.class) {
      return getOrCreateScopedObject(
        parameterContext,
        extensionContext,
        VERTX_RX2_INSTANCE_KEY,
        VERTX_RX2_INSTANCE_CREATOR_KEY,
        key -> new ScopedObject<>(io.vertx.reactivex.core.Vertx.vertx(), closeRx2Vertx()));
    }
    if (type == VertxTestContext.class) {
      return newTestContext(extensionContext);
    }
    throw new IllegalStateException("Looks like the ParameterResolver needs a fix...");
  }

  private Object getOrCreateScopedObject(ParameterContext parameterContext, ExtensionContext extensionContext, String instanceKey, String phaseCreatorKey, Function<String, Object> creatorFunction) {
    Store store = store(extensionContext);
    if (extensionContext.getParent().isPresent()) {
      Store parentStore = store(extensionContext.getParent().get());
      if (parentStore.get(instanceKey) != null) {
        return unpack(parentStore.get(instanceKey));
      }
    }
    if (store.get(instanceKey) == null) {
      store.put(phaseCreatorKey, scopeFor(parameterContext.getDeclaringExecutable()));
    }
    return unpack(store.getOrComputeIfAbsent(instanceKey, creatorFunction));
  }

  private Object unpack(Object object) {
    if (object instanceof Supplier) {
      return ((Supplier) object).get();
    }
    return object;
  }

  private VertxTestContext newTestContext(ExtensionContext extensionContext) {
    Store store = store(extensionContext);
    ContextList contexts = (ContextList) store.getOrComputeIfAbsent(TEST_CONTEXT_KEY, key -> new ContextList());
    VertxTestContext newTestContext = new VertxTestContext();
    contexts.add(newTestContext);
    return newTestContext;
  }

  private CreationScope scopeFor(Executable injectionTarget) {
    if (isAnnotated(injectionTarget, BeforeAll.class)) {
      return CreationScope.BEFORE_ALL;
    } else if (isAnnotated(injectionTarget, BeforeEach.class)) {
      return CreationScope.BEFORE_EACH;
    }
    return CreationScope.TEST;
  }

  private Store store(ExtensionContext extensionContext) {
    return extensionContext.getStore(Namespace.create(VertxExtension.class, extensionContext));
  }

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    // Not much we can do here ATM
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    // We may wait on test contexts from @AfterAll methods
    joinActiveTestContexts(context);
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    // We may wait on test contexts from @BeforeAll methods
    joinActiveTestContexts(context);
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    // We may wait on test contexts from @AfterEach methods
    joinActiveTestContexts(context);
  }

  @Override
  public void beforeTestExecution(ExtensionContext context) throws Exception {
    // We may wait on test contexts from @BeforeEach methods
    joinActiveTestContexts(context);
  }

  @Override
  public void afterTestExecution(ExtensionContext context) throws Exception {
    // We may wait on the test context from a test
    joinActiveTestContexts(context);
  }

  private void joinActiveTestContexts(ExtensionContext extensionContext) throws Exception {
    if (extensionContext.getExecutionException().isPresent()) {
      return;
    }

    ContextList currentContexts = store(extensionContext).remove(TEST_CONTEXT_KEY, ContextList.class);
    if (currentContexts != null) {
      for (VertxTestContext context : currentContexts) {
        int timeoutDuration = DEFAULT_TIMEOUT_DURATION;
        TimeUnit timeoutUnit = DEFAULT_TIMEOUT_UNIT;
        Optional<Method> testMethod = extensionContext.getTestMethod();
        if (testMethod.isPresent() && testMethod.get().isAnnotationPresent(Timeout.class)) {
          Timeout annotation = extensionContext.getRequiredTestMethod().getAnnotation(Timeout.class);
          timeoutDuration = annotation.value();
          timeoutUnit = annotation.timeUnit();
        } else if (extensionContext.getRequiredTestClass().isAnnotationPresent(Timeout.class)) {
          Timeout annotation = extensionContext.getRequiredTestClass().getAnnotation(Timeout.class);
          timeoutDuration = annotation.value();
          timeoutUnit = annotation.timeUnit();
        }
        if (context.awaitCompletion(timeoutDuration, timeoutUnit)) {
          if (context.failed()) {
            Throwable throwable = context.causeOfFailure();
            if (throwable instanceof Exception) {
              throw (Exception) throwable;
            } else {
              throw new AssertionError(throwable);
            }
          }
        } else {
          throw new TimeoutException("The test execution timed out. Make sure your asynchronous code "
            + "includes calls to either VertxTestContext#completeNow(), VertxTestContext#failNow() "
            + "or Checkpoint#flag()");
        }
      }
    }

    if (extensionContext.getParent().isPresent()) {
      joinActiveTestContexts(extensionContext.getParent().get());
    }
  }

  @FunctionalInterface
  private interface ThrowingConsumer<T> {
    void accept(T obj) throws Exception;
  }

  private static class ScopedObject <T> implements Supplier<T>, ExtensionContext.Store.CloseableResource {

    private final T object;
    private final ThrowingConsumer<T> cleaner;

    ScopedObject(T object, ThrowingConsumer<T> cleaner) {
      this.object = object;
      this.cleaner = cleaner;
    }

    @Override
    public void close() throws Throwable {
      cleaner.accept(object);
    }

    @Override
    public T get() {
      return object;
    }
  }

  private ThrowingConsumer<Vertx> closeRegularVertx() {
    return vertx -> {
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Throwable> errorBox = new AtomicReference<>();
      vertx.close(ar -> {
        if (ar.failed()) {
          errorBox.set(ar.cause());
        }
        latch.countDown();
      });
      if (!latch.await(DEFAULT_TIMEOUT_DURATION, DEFAULT_TIMEOUT_UNIT)) {
        throw new TimeoutException("Closing the Vertx context timed out");
      }
      Throwable throwable = errorBox.get();
      if (throwable != null) {
        if (throwable instanceof Exception) {
          throw (Exception) throwable;
        } else {
          throw new VertxException(throwable);
        }
      }
    };
  }

  private ThrowingConsumer<io.vertx.rxjava.core.Vertx> closeRx1Vertx() {
    return vertx -> {
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Throwable> errorBox = new AtomicReference<>();
      vertx.close(ar -> {
        if (ar.failed()) {
          errorBox.set(ar.cause());
        }
        latch.countDown();
      });
      if (!latch.await(DEFAULT_TIMEOUT_DURATION, DEFAULT_TIMEOUT_UNIT)) {
        throw new TimeoutException("Closing the Vertx context timed out");
      }
      Throwable throwable = errorBox.get();
      if (throwable != null) {
        if (throwable instanceof Exception) {
          throw (Exception) throwable;
        } else {
          throw new VertxException(throwable);
        }
      }
    };
  }

  private ThrowingConsumer<io.vertx.reactivex.core.Vertx> closeRx2Vertx() {
    return vertx -> {
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Throwable> errorBox = new AtomicReference<>();
      vertx.close(ar -> {
        if (ar.failed()) {
          errorBox.set(ar.cause());
        }
        latch.countDown();
      });
      if (!latch.await(DEFAULT_TIMEOUT_DURATION, DEFAULT_TIMEOUT_UNIT)) {
        throw new TimeoutException("Closing the Vertx context timed out");
      }
      Throwable throwable = errorBox.get();
      if (throwable != null) {
        if (throwable instanceof Exception) {
          throw (Exception) throwable;
        } else {
          throw new VertxException(throwable);
        }
      }
    };
  }
}
