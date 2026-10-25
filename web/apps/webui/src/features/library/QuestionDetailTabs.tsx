"use client";

import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

import styles from "./workspace.module.css";

type DetailTab = "answer" | "tutor";

export function QuestionDetailTabs({ answer, tutor }: { answer: ReactNode; tutor: ReactNode }) {
  const [selected, setSelected] = useState<DetailTab>("answer");
  const id = useId();
  const answerRef = useRef<HTMLButtonElement>(null);
  const tutorRef = useRef<HTMLButtonElement>(null);

  function handleKey(event: KeyboardEvent<HTMLButtonElement>) {
    let next: DetailTab;
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      next = selected === "answer" ? "tutor" : "answer";
    } else if (event.key === "Home") {
      next = "answer";
    } else if (event.key === "End") {
      next = "tutor";
    } else return;
    event.preventDefault();
    setSelected(next);
    (next === "answer" ? answerRef : tutorRef).current?.focus();
  }

  return (
    <div>
      <div className={styles.tabs} role="tablist" aria-label="作答与辅导">
        <button
          ref={answerRef}
          id={`${id}-answer-tab`}
          type="button"
          role="tab"
          aria-selected={selected === "answer"}
          aria-controls={`${id}-answer-panel`}
          tabIndex={selected === "answer" ? 0 : -1}
          onClick={() => setSelected("answer")}
          onKeyDown={handleKey}
          className={styles.tab}
        >
          当前作答
        </button>
        <button
          ref={tutorRef}
          id={`${id}-tutor-tab`}
          type="button"
          role="tab"
          aria-selected={selected === "tutor"}
          aria-controls={`${id}-tutor-panel`}
          tabIndex={selected === "tutor" ? 0 : -1}
          onClick={() => setSelected("tutor")}
          onKeyDown={handleKey}
          className={styles.tab}
        >
          AI 辅导
        </button>
      </div>
      {/* Keep loaded panels and view transforms. The viewer ignores zero-size observations while hidden. */}
      <div
        id={`${id}-answer-panel`}
        role="tabpanel"
        aria-labelledby={`${id}-answer-tab`}
        tabIndex={0}
        hidden={selected !== "answer"}
        className={styles.tabPanel}
      >
        {answer}
      </div>
      <div
        id={`${id}-tutor-panel`}
        role="tabpanel"
        aria-labelledby={`${id}-tutor-tab`}
        tabIndex={0}
        hidden={selected !== "tutor"}
        className={styles.tabPanel}
      >
        {tutor}
      </div>
    </div>
  );
}
