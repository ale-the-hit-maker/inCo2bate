package com.incusense.service;

import com.incusense.model.Alert;
import com.incusense.model.NotificationContact;

public interface AlertNotifier {

    /** Channel identifier this notifier handles, e.g. "EMAIL". */
    String channel();

    /** Deliver the alert to the given contact. Implementations must not throw. */
    void notify(NotificationContact contact, Alert alert);
}
