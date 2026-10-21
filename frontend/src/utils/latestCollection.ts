export interface CollectionState<T> {
  items: T[];
  loading: boolean;
  error: string;
}

/** Serializes visible collection state across overlapping reads and successful writes. */
export class LatestCollection<T> {
  private revision = 0;
  private listeners = new Set<() => void>();
  private state: CollectionState<T>;
  private readonly read: () => Promise<T[]>;

  constructor(read: () => Promise<T[]>, initialItems: T[] = []) {
    this.read = read;
    this.state = { items: initialItems, loading: true, error: '' };
  }

  getSnapshot = (): CollectionState<T> => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };

  async load(): Promise<void> {
    const current = ++this.revision;
    this.publish({ ...this.state, loading: true, error: '' });
    try {
      const items = await this.read();
      if (this.isCurrent(current)) this.publish({ items, loading: false, error: '' });
    } catch (error) {
      if (this.isCurrent(current)) {
        this.publish({ ...this.state, loading: false, error: error instanceof Error ? error.message : '未知错误' });
      }
    }
  }

  update(change: (items: T[]) => T[]): void {
    ++this.revision;
    this.publish({ items: change(this.state.items), loading: false, error: '' });
  }

  invalidate(): void {
    ++this.revision;
  }

  private isCurrent(revision: number): boolean {
    return revision === this.revision;
  }

  private publish(state: CollectionState<T>): void {
    this.state = state;
    this.listeners.forEach(listener => listener());
  }
}
