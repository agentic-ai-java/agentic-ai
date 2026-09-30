"use client";

import { Settings } from "lucide-react";

import { StreamConfigurationView } from "@/providers/Stream";
import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";

export function StudioSettingsButton() {
  return (
    <Sheet>
      <SheetTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          aria-label="Studio settings"
        >
          <Settings className="h-4 w-4" />
        </Button>
      </SheetTrigger>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>Studio Settings</SheetTitle>
        </SheetHeader>
        <div className="px-4 pb-4">
          <StreamConfigurationView />
        </div>
      </SheetContent>
    </Sheet>
  );
}
