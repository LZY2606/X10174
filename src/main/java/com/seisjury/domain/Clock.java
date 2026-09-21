package com.seisjury.domain;

/** Wall clock; tests replace this bean for deterministic timestamps. */
@FunctionalInterface
public interface Clock {
    long nowMs();
}
