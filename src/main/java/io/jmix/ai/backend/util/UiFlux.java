package io.jmix.ai.backend.util;

import com.vaadin.flow.component.UI;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

/**
 * Fluent wrapper over {@link Flux} that executes all callbacks
 * inside {@link UI#access}, so handlers can safely mutate Vaadin components.
 *
 * <p>Usage:
 * <pre>
 * UiFlux.from(flux, ui)
 *     .doOnNextInUi(event -> label.setText(event.text()))
 *     .doOnErrorInUi(e -> notifications.show(e.getMessage()))
 *     .doOnCompleteInUi(() -> input.setEnabled(true))
 *     .subscribe();
 * </pre>
 */
public class UiFlux<T> {

    private Flux<T> flux;
    private final UI ui;

    private UiFlux(Flux<T> flux, UI ui) {
        this.flux = flux;
        this.ui = ui;
    }

    public static <T> UiFlux<T> from(Flux<T> flux, UI ui) {
        return new UiFlux<>(flux, ui);
    }

    public UiFlux<T> doOnNextInUi(Consumer<T> handler) {
        flux = flux.doOnNext(item -> ui.access(() -> handler.accept(item)));
        return this;
    }

    public UiFlux<T> doOnErrorInUi(Consumer<Throwable> handler) {
        flux = flux.doOnError(error -> ui.access(() -> handler.accept(error)));
        return this;
    }

    public UiFlux<T> doOnCompleteInUi(Runnable handler) {
        flux = flux.doOnComplete(() -> ui.access(handler::run));
        return this;
    }

    public Disposable subscribe() {
        return flux.subscribe();
    }
}
