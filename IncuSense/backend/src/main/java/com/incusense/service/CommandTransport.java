package com.incusense.service;

/**
 * Astrazione del canale di uscita verso i nodi (topic {@code .../commands}).
 * Disaccoppia {@link AutoCalibrationService} dal trasporto MQTT concreto, cosi' che la
 * logica di decisione resti testabile senza un broker (vedi test unitari + §8 verifica).
 */
public interface CommandTransport {

    /**
     * Pubblica un payload JSON di comando sul topic dato.
     *
     * @return true se l'invio e' stato accettato dal trasporto, false altrimenti.
     */
    boolean publish(String topic, String jsonPayload);
}
