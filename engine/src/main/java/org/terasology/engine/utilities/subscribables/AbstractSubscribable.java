// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.utilities.subscribables;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeListenerProxy;
import java.beans.PropertyChangeSupport;

public abstract class AbstractSubscribable implements Subscribable {

    protected transient PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);

    @Override
    public void subscribe(PropertyChangeListener changeListener) {
        this.propertyChangeSupport.addPropertyChangeListener(changeListener);
    }

    @Override
    public void unsubscribe(PropertyChangeListener changeListener) {
        this.propertyChangeSupport.removePropertyChangeListener(changeListener);
    }

    @Override
    public void subscribe(String propertyName, PropertyChangeListener changeListener) {
        this.propertyChangeSupport.addPropertyChangeListener(propertyName, changeListener);
    }

    @Override
    public void unsubscribe(String propertyName, PropertyChangeListener changeListener) {
        this.propertyChangeSupport.removePropertyChangeListener(propertyName, changeListener);
    }

    /**
     * Takes a listener off every property it is subscribed to, whichever way it subscribed.
     * <p>
     * {@link #unsubscribe(PropertyChangeListener)} does not do this, and the difference is easy to miss: a listener
     * registered for a named property is held inside a {@link PropertyChangeListenerProxy}, which that method never
     * matches, so it silently removes nothing. A listener that outlives nothing - a render node, disposed when its
     * game ends - has to be taken off the list this way, or it keeps being notified long after the module assets it
     * reaches for have been unloaded.
     *
     * @param changeListener the listener to remove; one that was never subscribed is ignored.
     */
    public void unsubscribeFromAllProperties(PropertyChangeListener changeListener) {
        // getPropertyChangeListeners returns a copy, so removing while walking it is safe.
        for (PropertyChangeListener registered : this.propertyChangeSupport.getPropertyChangeListeners()) {
            if (registered instanceof PropertyChangeListenerProxy) {
                PropertyChangeListenerProxy proxy = (PropertyChangeListenerProxy) registered;
                if (proxy.getListener() == changeListener) {
                    this.propertyChangeSupport.removePropertyChangeListener(proxy.getPropertyName(), changeListener);
                }
            } else if (registered == changeListener) {
                this.propertyChangeSupport.removePropertyChangeListener(changeListener);
            }
        }
    }

}
