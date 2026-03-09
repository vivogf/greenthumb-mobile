import { differenceInDays, parseISO, isToday, format } from 'date-fns';
import { ru as ruLocale, enUS as enLocale } from 'date-fns/locale';

/**
 * Calculates how many days until (or since) a plant needs to be watered.
 * Negative = overdue, 0 = today, positive = days remaining.
 */
export function getDaysUntilWatering(
  lastWateredDate: string,
  frequencyDays: number,
): number {
  const last = parseISO(lastWateredDate);
  const nextWatering = new Date(last);
  nextWatering.setDate(nextWatering.getDate() + frequencyDays);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  nextWatering.setHours(0, 0, 0, 0);
  return differenceInDays(nextWatering, today);
}

/**
 * Returns a plant's watering status category.
 */
export function getWateringStatus(
  lastWateredDate: string,
  frequencyDays: number,
): 'overdue' | 'today' | 'healthy' {
  const days = getDaysUntilWatering(lastWateredDate, frequencyDays);
  if (days < 0) return 'overdue';
  if (days === 0) return 'today';
  return 'healthy';
}

/**
 * Today's date in YYYY-MM-DD format (local time), used when marking
 * a plant as watered/fertilized/etc.
 */
export function todayString(): string {
  return format(new Date(), 'yyyy-MM-dd');
}

/**
 * Formats a date string for display.
 */
export function formatDate(
  dateStr: string,
  language: string = 'en',
): string {
  const locale = language === 'ru' ? ruLocale : enLocale;
  return format(parseISO(dateStr), 'd MMM yyyy', { locale });
}

/**
 * Checks if a date string represents today.
 */
export function isDateToday(dateStr: string): boolean {
  return isToday(parseISO(dateStr));
}

/**
 * How many days ago a date was (for care history display).
 */
export function daysAgo(dateStr: string): number {
  return differenceInDays(new Date(), parseISO(dateStr));
}
