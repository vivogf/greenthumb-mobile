/**
 * Shared TypeScript types and Zod validation schemas.
 *
 * This is a mobile-adapted version of shared/schema.ts from GreenThumbPWA.
 * Drizzle ORM table definitions are removed — only types and Zod schemas remain,
 * since the mobile app communicates with the backend via REST API (no direct DB).
 */
import { z } from 'zod';

// ---------------------------------------------------------------------------
// User
// ---------------------------------------------------------------------------

export interface User {
  id: number;
  name: string | null;
  notification_time: string | null;
  recovery_key: string;
  created_at: string;
}

// ---------------------------------------------------------------------------
// Plant
// ---------------------------------------------------------------------------

export interface Plant {
  id: string;
  user_id: string;
  name: string;
  location: string;
  photo_url: string;
  water_frequency_days: number;
  last_watered_date: string;
  fertilize_frequency_days: number | null;
  last_fertilized_date: string | null;
  repot_frequency_months: number | null;
  last_repotted_date: string | null;
  prune_frequency_months: number | null;
  last_pruned_date: string | null;
  notes: string | null;
  created_at: string;
}

export const insertPlantSchema = z.object({
  name: z.string().min(1, 'Name is required'),
  location: z.string().min(1, 'Location is required'),
  photo_url: z.string().default(''),
  water_frequency_days: z.number().int().positive(),
  last_watered_date: z.string(),
  notes: z.string().optional().default(''),
  fertilize_frequency_days: z.number().int().positive().optional(),
  last_fertilized_date: z.string().optional(),
  repot_frequency_months: z.number().int().positive().optional(),
  last_repotted_date: z.string().optional(),
  prune_frequency_months: z.number().int().positive().optional(),
  last_pruned_date: z.string().optional(),
});

export type InsertPlant = z.infer<typeof insertPlantSchema>;

// ---------------------------------------------------------------------------
// Push subscriptions (Expo Push Token — used in Phase 6)
// ---------------------------------------------------------------------------

export interface ExpoPushSubscription {
  id: number;
  user_id: number;
  expo_push_token: string;
  created_at: string;
}
