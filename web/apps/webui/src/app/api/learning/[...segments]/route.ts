import type { NextRequest } from "next/server";
import { handleLearningRequest } from "@/lib/api/learning-v2-server";

type Context = { params: Promise<{ segments: string[] }> };
export async function GET(request: NextRequest, context: Context): Promise<Response> {
  return handleLearningRequest(request, (await context.params).segments, "GET");
}
export async function POST(request: NextRequest, context: Context): Promise<Response> {
  return handleLearningRequest(request, (await context.params).segments, "POST");
}
export async function PUT(request: NextRequest, context: Context): Promise<Response> {
  return handleLearningRequest(request, (await context.params).segments, "PUT");
}
export async function DELETE(request: NextRequest, context: Context): Promise<Response> {
  return handleLearningRequest(request, (await context.params).segments, "DELETE");
}
