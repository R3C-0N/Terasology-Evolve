// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.utilities.subscribables;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What it takes to stop listening.
 * <p>
 * A render node subscribes to the rendering config by property name, and is disposed when its game ends while the
 * config lives on. The first test below pins the trap that made that leak invisible for so long: the obvious call,
 * {@link AbstractSubscribable#unsubscribe(PropertyChangeListener)}, silently removes nothing in that case, because a
 * named subscription is held inside a proxy it never matches.
 */
class AbstractSubscribableTest {

    private static final String PROPERTY = "bloom";
    private static final String OTHER_PROPERTY = "lightShafts";

    private Subject subject;
    private Recorder listener;

    @BeforeEach
    void setUp() {
        subject = new Subject();
        listener = new Recorder();
    }

    @Test
    void unsubscribeByListenerAloneLeavesANamedSubscriptionInPlace() {
        subject.subscribe(PROPERTY, listener);

        subject.unsubscribe(listener);
        subject.change(PROPERTY);

        assertEquals(1, listener.events.size(),
                "unsubscribe(listener) cannot match a subscription taken by property name - this is the trap");
    }

    @Test
    void unsubscribeFromAllPropertiesRemovesEveryNamedSubscription() {
        subject.subscribe(PROPERTY, listener);
        subject.subscribe(OTHER_PROPERTY, listener);

        subject.unsubscribeFromAllProperties(listener);
        subject.change(PROPERTY);
        subject.change(OTHER_PROPERTY);

        assertTrue(listener.events.isEmpty(), "the listener should hear nothing once taken off every property");
    }

    @Test
    void unsubscribeFromAllPropertiesRemovesAGeneralSubscriptionToo() {
        subject.subscribe(listener);

        subject.unsubscribeFromAllProperties(listener);
        subject.change(PROPERTY);

        assertTrue(listener.events.isEmpty(), "a listener subscribed to everything should also be removed");
    }

    @Test
    void unsubscribeFromAllPropertiesLeavesOtherListenersAlone() {
        Recorder other = new Recorder();
        subject.subscribe(PROPERTY, listener);
        subject.subscribe(PROPERTY, other);

        subject.unsubscribeFromAllProperties(listener);
        subject.change(PROPERTY);

        assertTrue(listener.events.isEmpty(), "the removed listener should hear nothing");
        assertEquals(1, other.events.size(), "the listener that was not removed should still hear the change");
    }

    @Test
    void unsubscribingSomethingThatNeverSubscribedChangesNothing() {
        subject.subscribe(PROPERTY, listener);

        subject.unsubscribeFromAllProperties(new Recorder());
        subject.change(PROPERTY);

        assertEquals(1, listener.events.size(), "removing a stranger must not disturb the listeners that are there");
    }

    /** A subscribable that can be made to fire, standing in for a config. */
    private static final class Subject extends AbstractSubscribable {
        void change(String property) {
            propertyChangeSupport.firePropertyChange(property, false, true);
        }
    }

    private static final class Recorder implements PropertyChangeListener {
        private final List<PropertyChangeEvent> events = new ArrayList<>();

        @Override
        public void propertyChange(PropertyChangeEvent event) {
            events.add(event);
        }
    }
}
