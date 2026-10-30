package dev.ghost.nearbyim.core;

/** Presentation of real saved state; there is deliberately no synthetic READ state. */
public enum MessageReceipt {
    WAITING, SENT, DELIVERED, READ, UNCONFIRMED, FAILED;
    public static MessageReceipt of(String state){return switch(state==null?"":state){
        case "delivered" -> DELIVERED;
        case "read" -> READ;
        case "sent" -> SENT;
        case "failed" -> FAILED;
        case "unknown","interrupted","canceled","paused","sourceUnavailable" -> UNCONFIRMED;
        default -> WAITING;
    };}
    public String textKey(){return switch(this){case WAITING->"messageSending";case SENT->"sent";case DELIVERED->"delivered";case READ->"read";case FAILED->"messageSendFailed";case UNCONFIRMED->"unknown";};}
    /** Coordinates in a 20 by 16 box, shared by native Canvas and Swing painters. */
    public void draw(Ink ink){
        switch(this){
            case SENT,DELIVERED,READ -> {ink.line(2,8,6,12);ink.line(6,12,14,4);if(this==DELIVERED||this==READ){ink.line(10,12,18,4);}}
            case WAITING -> {ink.circle(10,8,6);ink.line(10,4,10,8);ink.line(10,8,13,10);}
            case FAILED -> {ink.line(10,2,18,14);ink.line(18,14,2,14);ink.line(2,14,10,2);ink.line(10,6,10,9);ink.line(10,12,10,12.2f);}
            case UNCONFIRMED -> {ink.circle(10,8,6);ink.line(10,4,10,8);ink.line(10,11,10,11.2f);}
        }
    }
    public interface Ink {void line(float x1,float y1,float x2,float y2);void circle(float x,float y,float radius);}
}
