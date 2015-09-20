package org.rakam.kume.transport;

import org.rakam.kume.Member;

/**
 * Created by buremba <Burak Emre Kabakcı> on 10/12/14 23:06.
 */
public interface OperationContext<R> {
    void reply(R obj);
    Member getSender();
    int serviceId();
}
