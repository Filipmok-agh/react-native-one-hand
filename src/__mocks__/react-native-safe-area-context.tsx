import { createContext } from 'react';

/**
 * Minimal stand-in for the OPTIONAL peer dependency. Only `SafeAreaInsetsContext` is
 * reproduced, because that is the entire surface `DockedInsets` touches — it reads the
 * context and re-provides it with `top: 0` while docked.
 */
export type EdgeInsets = { top: number; right: number; bottom: number; left: number };

export const SafeAreaInsetsContext = createContext<EdgeInsets | null>(null);
