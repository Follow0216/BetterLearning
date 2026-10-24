import { jsonNoStore } from "@/lib/api/auth-response";
import { libraryFailureResponse } from "@/lib/api/library-bff";
import { getTutorTurns } from "@/lib/api/library-server";

interface TutorTurnsRouteContext {
  params: Promise<{ questionId: string }>;
}

export async function GET(_request: Request, context: TutorTurnsRouteContext): Promise<Response> {
  const { questionId } = await context.params;
  const result = await getTutorTurns(questionId);
  if (!result.ok) {
    return libraryFailureResponse(result.failure);
  }
  return jsonNoStore(result.data);
}
