package brooklyn.event.basic;

import brooklyn.event.Event;


public interface LogEvent<T> extends Event<T> {
    /** @see Event#getSensor() */
    public LogSensor<T> getSensor();
}