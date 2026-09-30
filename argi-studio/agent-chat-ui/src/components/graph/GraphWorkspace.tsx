"use client";

import React, { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { ArrowLeft, Plus, PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { GraphDiagram } from "./GraphDiagram";
import { NodeTimeline } from "./NodeTimeline";
import { StateInspector } from "./StateInspector";
import { GraphChatArea } from "./GraphChatArea";
import { useGraphThreads } from "@/providers/GraphThread";
import { StudioSettingsButton } from "@/components/StudioSettingsButton";

export function GraphWorkspace() {
  const {
    graphName,
    threads,
    currentThreadId,
    setCurrentThreadId,
    createThread,
    isLoading,
  } = useGraphThreads();
  const [diagramOpen, setDiagramOpen] = useState(true);

  if (!graphName) return null;

  return (
    <div className="flex h-screen flex-col bg-slate-50">
      <header className="flex h-14 shrink-0 items-center justify-between border-b bg-white px-4">
        <div className="flex items-center gap-3">
          <Link href="/index.html">
            <Button
              variant="ghost"
              size="sm"
            >
              <ArrowLeft className="mr-2 h-4 w-4" />
              Back
            </Button>
          </Link>
          <span className="text-lg font-semibold">Graph: {graphName}</span>
        </div>
        <div className="flex items-center gap-2">
          <StudioSettingsButton />
          <Button
            variant="outline"
            size="sm"
            onClick={() => createThread()}
            disabled={isLoading}
          >
            <Plus className="mr-2 h-4 w-4" />
            New thread
          </Button>
          {threads.length > 0 && (
            <select
              value={currentThreadId ?? ""}
              onChange={(e) => setCurrentThreadId(e.target.value || null)}
              className="border-input bg-background rounded-md border px-2 py-1 text-sm"
            >
              <option value="">Select thread...</option>
              {threads.map((t) => (
                <option
                  key={t.thread_id}
                  value={t.thread_id}
                >
                  {t.thread_id.slice(0, 8)}...
                </option>
              ))}
            </select>
          )}
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        {diagramOpen && (
          <aside className="flex w-80 shrink-0 flex-col overflow-y-auto border-r bg-white">
            <div className="flex items-center justify-between border-b p-2">
              <span className="text-sm font-medium">Graph Diagram</span>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setDiagramOpen(false)}
              >
                <PanelLeftClose className="h-4 w-4" />
              </Button>
            </div>
            <div className="min-h-0 flex-1 overflow-auto p-3">
              <GraphDiagram graphName={graphName} />
            </div>
            <div className="bg-muted/20 border-t p-3">
              <GraphChatArea />
            </div>
          </aside>
        )}
        {!diagramOpen && (
          <Button
            variant="outline"
            size="sm"
            className="absolute top-20 left-2 z-10"
            onClick={() => setDiagramOpen(true)}
          >
            <PanelLeftOpen className="h-4 w-4" />
          </Button>
        )}

        <main className="flex min-w-0 flex-1 flex-col gap-4 overflow-hidden p-4">
          <div className="shrink-0">
            <NodeTimeline />
          </div>
          <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
            <StateInspector />
          </div>
        </main>
      </div>
    </div>
  );
}
