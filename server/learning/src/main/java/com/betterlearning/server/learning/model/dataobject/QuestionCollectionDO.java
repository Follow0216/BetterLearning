package com.betterlearning.server.learning.model.dataobject;

/** 真实题集与当前账号题数投影；待整理不占数据库行。 */
public class QuestionCollectionDO {

    private Long collectionId;

    private Long accountId;

    private String name;

    private Long parentCollectionId;

    private Long questionCount;

    /** 返回 collectionId。 */
    public Long getCollectionId() {
        return collectionId;
    }

    /** 设置 collectionId。 */
    public void setCollectionId(Long collectionId) {
        this.collectionId = collectionId;
    }

    /** 返回 accountId。 */
    public Long getAccountId() {
        return accountId;
    }

    /** 设置 accountId。 */
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    /** 返回 name。 */
    public String getName() {
        return name;
    }

    /** 设置 name。 */
    public void setName(String name) {
        this.name = name;
    }

    /** 返回父题集 ID，根级为 null。 */
    public Long getParentCollectionId() {
        return parentCollectionId;
    }

    /** 设置父题集 ID。 */
    public void setParentCollectionId(Long parentCollectionId) {
        this.parentCollectionId = parentCollectionId;
    }

    /** 返回 questionCount。 */
    public Long getQuestionCount() {
        return questionCount;
    }

    /** 设置 questionCount。 */
    public void setQuestionCount(Long questionCount) {
        this.questionCount = questionCount;
    }
}
