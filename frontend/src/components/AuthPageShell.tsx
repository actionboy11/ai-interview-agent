import { Sparkles } from 'lucide-react';
import type { ReactNode } from 'react';

export const authInputClass = 'mt-1 w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-slate-900 outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-800 dark:text-white';
export const authButtonClass = 'flex w-full items-center justify-center gap-2 rounded-xl bg-primary-600 px-4 py-3 font-medium text-white transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-50';

export default function AuthPageShell({ title, error, children }: { title: string; error: string; children: ReactNode }) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 to-indigo-50 px-4 py-10 dark:from-slate-900 dark:to-slate-800">
      <section className="w-full max-w-md rounded-2xl border border-slate-100 bg-white p-8 shadow-xl dark:border-slate-700 dark:bg-slate-900">
        <div className="mb-6 flex items-center gap-3"><Sparkles className="h-8 w-8 text-primary-500" /><span className="text-lg font-bold text-slate-900 dark:text-white">AI Interview</span></div>
        <h1 className="mb-6 text-2xl font-bold text-slate-900 dark:text-white">{title}</h1>
        {error && <p role="alert" className="mb-4 rounded-xl bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">{error}</p>}
        {children}
      </section>
    </main>
  );
}
