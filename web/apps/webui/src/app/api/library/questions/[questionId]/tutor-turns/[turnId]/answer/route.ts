import { jsonNoStore } from "@/lib/api/auth-response";
import { libraryFailureResponse } from "@/lib/api/library-bff";
import { getTutorTurnAnswerManifest } from "@/lib/api/library-server";

interface TutorTurnAnswerRouteContext {
  params: Promise<{ questionId: string; turnId: string }>;
}

export async function GET(
  _request: Request,
  context: TutorTurnAnswerRouteContext,
): Promise<Response> {
  const { questionId, turnId } = await context.params;
  const result = await getTutorTurnAnswerManifest(questionId, turnId);
  if (!result.ok) {
    return libraryFailureResponse(result.failure);
  }
  return jsonNoStore(result.data);
}
