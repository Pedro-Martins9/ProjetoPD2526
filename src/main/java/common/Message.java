package common;

import java.io.Serializable;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        LOGIN_REQUEST,
        LOGIN_RESPONSE,
        REGISTER_REQUEST,
        REGISTER_RESPONSE,
        CREATE_QUESTION,
        CREATE_QUESTION_RESPONSE,
        LIST_QUESTIONS,
        LIST_QUESTIONS_RESPONSE,
        GET_QUESTIONS,
        GET_QUESTIONS_RESPONSE,
        GET_QUESTION,
        SUBMIT_ANSWER,
        SUBMIT_ANSWER_RESPONSE,
        HEARTBEAT,
        DB_SYNC_REQUEST,
        DB_SYNC_RESPONSE,
        EXPORT_CSV,
        EXPORT_CSV_RESPONSE,
        EDIT_QUESTION,
        EDIT_QUESTION_RESPONSE,
        DELETE_QUESTION,
        DELETE_QUESTION_RESPONSE,
        GET_QUESTION_ANSWERS,
        GET_QUESTION_ANSWERS_RESPONSE
    }

    private Type type;
    private Object content;

    public Message(Type type, Object content) {
        this.type = type;
        this.content = content;
    }

    public Type getType() {
        return type;
    }

    public Object getContent() {
        return content;
    }
}
