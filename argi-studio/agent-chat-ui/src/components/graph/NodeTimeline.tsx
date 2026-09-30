"use client";

import React from "react";
import { useGraphStream } from "@/providers/GraphStream";
import { cn } from "@/lib/utils";

export function NodeTimeline() {
  const { nodeOutputs, selectedNodeIndex, setSelectedNodeIndex } =
    useGraphStream();

  if (nodeOutputs.length === 0) {
    return (
      <div className="bg-muted/30 text-muted-foreground rounded-lg border p-4 text-sm">
        No nodes executed yet. Send a message to run the graph.
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded-lg border bg-white">
      <div className="bg-muted/50 border-b px-3 py-2 text-sm font-medium">
        Execution Timeline
      </div>
      <ul className="divide-border max-h-[280px] divide-y overflow-y-auto">
        {nodeOutputs.map((out, i) => {
          const hasState = out.state && Object.keys(out.state).length > 0;
          const hasOutput = !!(out.chunk || out.message?.content);
          const isSelected = selectedNodeIndex === i;
          return (
            <li
              key={`${out.node}-${i}`}
              role="button"
              tabIndex={0}
              onClick={() => setSelectedNodeIndex(isSelected ? null : i)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  setSelectedNodeIndex(isSelected ? null : i);
                }
              }}
              className={cn(
                "flex cursor-pointer flex-col gap-1 px-3 py-2.5 text-sm transition-colors",
                isSelected && "bg-primary/10 border-l-primary border-l-2",
                !isSelected && "hover:bg-muted/50",
                (out.node === "__start__" || out.node === "START") &&
                  "text-muted-foreground",
              )}
            >
              <div className="flex items-center gap-2">
                <span
                  className={cn(
                    "flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-medium",
                    isSelected
                      ? "bg-primary text-primary-foreground"
                      : "bg-primary/10 text-primary",
                  )}
                >
                  {out.index + 1}
                </span>
                <span className="font-mono font-medium">{out.node}</span>
                {out.agent && (
                  <span className="text-muted-foreground text-xs">
                    ({out.agent})
                  </span>
                )}
              </div>
              {(hasOutput || hasState) && (
                <div className="text-muted-foreground flex gap-2 pl-8 text-xs">
                  {hasState && <span>state</span>}
                  {hasOutput && (
                    <span className="truncate">
                      {(() => {
                        const t = (out.chunk || out.message?.content) ?? "";
                        return t.length > 40 ? t.slice(0, 40) + "…" : t;
                      })()}
                    </span>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
