import Image from "next/image";

import illustration from "@/assets/auth/login-illustration.png";
import { AuthForm } from "@/features/auth/AuthForm";

export default function Home() {
  return (
    <div className="min-h-dvh bg-[var(--auth-bg)] px-6 py-8 text-[var(--auth-text)] sm:py-10">
      <div className="mx-auto flex w-full max-w-[1160px] flex-col">
        <header className="w-full">
          {/* prettier-ignore */}
          <p aria-label="BetterLearning" className="auth-wordmark text-[var(--auth-primary)]">
            <span className="sm:text-[var(--auth-brand-deep)]">Better</span><span>Learning</span>
          </p>
        </header>
        <main className="mt-8 flex w-full flex-col items-center gap-8 min-[960px]:mt-14 min-[960px]:flex-row min-[960px]:items-center min-[960px]:justify-between min-[960px]:gap-16">
          <section
            aria-label="产品说明"
            className="flex w-full flex-col items-center text-center min-[960px]:flex-1 min-[960px]:items-start min-[960px]:text-left"
          >
            <Image
              src={illustration}
              alt=""
              aria-hidden="true"
              priority
              width={1536}
              height={1024}
              className="h-[180px] w-auto max-w-full object-contain min-[960px]:h-[360px]"
            />
            <p className="mt-6 text-base text-[var(--auth-muted)]">拍照提题 · 手写演算 · AI 解答</p>
          </section>
          <section aria-label="账号" className="w-full min-[960px]:w-[440px] min-[960px]:shrink-0">
            <div className="mx-auto w-full max-w-[420px] rounded-3xl border border-[var(--auth-border)] bg-white p-6 shadow-[0_18px_50px_-28px_rgba(21,39,68,0.28)] min-[960px]:max-w-none min-[960px]:p-10">
              <AuthForm />
            </div>
          </section>
        </main>
      </div>
    </div>
  );
}
