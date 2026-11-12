package common;

/**
 * Message types of the AstroNet application-layer protocol.
 *
 * Every line sent over a TCP socket (station <-> server) is one {@link Message}
 * whose "messageType" field holds one of these values.
 *
 *   Station -> Server : REGISTER, OBSERVATION_ALERT, VERIFICATION_RESPONSE, HEARTBEAT, DISCONNECT
 *   Server  -> Station: REGISTER_ACK, FOLLOW_UP_REQUEST, FINAL_RESULT, ERROR, DISCONNECT
 *
 * NOTE: this file is identical in AstroNet-Server and AstroNet-Station.
 */
public enum MessageType {

    /** Station introduces itself. Payload: StationInfo. Must be the first message on a new connection. */
    REGISTER,

    /** Server accepts a registration. Payload: {"accepted", "message", "connectedStations", ...}. */
    REGISTER_ACK,

    /** Station reports a possible astronomical event. Payload: AstronomicalEvent. */
    OBSERVATION_ALERT,

    /** Server asks another station to observe the same RA/Dec. Payload: AstronomicalEvent. */
    FOLLOW_UP_REQUEST,

    /** Station answers a follow-up request. Payload: VerificationResult. */
    VERIFICATION_RESPONSE,

    /** Server announces the verification decision to all stations. Payload: VerificationResult.FinalReport. */
    FINAL_RESULT,

    /** Periodic "I am still alive" message from a station. Payload: {"status": "..."}. */
    HEARTBEAT,

    /** Something went wrong. Payload: {"reason": "..."}. */
    ERROR,

    /** Either side announces that it is closing the connection. Payload: {"reason": "..."}. */
    DISCONNECT
}
