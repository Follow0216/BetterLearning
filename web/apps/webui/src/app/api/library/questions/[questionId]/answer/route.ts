import { jsonNoStore } from "@/lib/api/auth-response";
import { libraryFailureResponse } from "@/lib/api/library-bff";
import { getSavedAnswer } from "@/lib/api/library-server";

interface AnswerRouteContext {
  params: Promise<{ questionId: string }>;
}

export async function GET(_request: Request, context: AnswerRouteContext): Promise<Response> {
  const { questionId } = await context.params;
  const result = await getSavedAnswer(questionId);
  if (!result.ok) {
    return libraryFailureResponse(result.failure);
  }
  return jsonNoStore(result.data);
}