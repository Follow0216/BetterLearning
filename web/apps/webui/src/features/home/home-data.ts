import type { AuthUserProfile } from "@/lib/api/auth-contract";
import type {
  CollectionList,
  CollectionSummary,
  QuestionDetail,
  QuestionPage,
} from "@/lib/api/library-contract";
import type { LibraryResult } from "@/lib/api/library-server";

export type HomeProfileResult =
  { ok: true; user: AuthUserProfile } | { ok: false; status: number; message: string };

export interface HomeReadResults {
  profile: HomeProfileResult;
  questions: LibraryResult<QuestionPage>;
  collections: LibraryResult<CollectionList>;
  preview?: LibraryResult<QuestionDetail> | null;
}

/** No private section may be rendered when another read in the same batch rejects the session. */
export function requiresHomeSignIn(results: HomeReadResults): boolean {
  return (
    (!results.profile.ok && results.profile.status === 401) ||
    [results.questions, results.collections, results.preview].some(
      (result) =>
        result !== null &&
        result !== undefined &&
        !result.ok &&
        (result.failure.status === 401 || result.failure.kind === "unauthenticated"),
    )
  );
}

export function selectHomeCollections(items: CollectionSummary[], limit = 5): CollectionSummary[] {
  return items.filter((item) => !item.system).slice(0, Math.max(0, limit));
}
