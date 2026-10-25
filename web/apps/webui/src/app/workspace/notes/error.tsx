"use client";
export default function ErrorPage({ reset }: { reset: () => void }) {
  return (
    <div role="alert" className="p-6">
      <p>笔记页面暂时无法显示。</p>
      <button className="mt-3 underline" onClick={reset}>
        重试
      </button>
    </div>
  );
}
