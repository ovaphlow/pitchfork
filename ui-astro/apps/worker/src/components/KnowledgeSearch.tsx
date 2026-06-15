import { useState, useEffect, useCallback } from "react";
import { listKnowledgeEntries, type KnowledgeEntry, listKnowledgeCategories, type KnowledgeCategory } from "@pitchfork/shared";
import { Input, Badge, LoadingSpinner } from "@pitchfork/ui";

export default function KnowledgeSearch() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<KnowledgeEntry[]>([]);
  const [categories, setCategories] = useState<KnowledgeCategory[]>([]);
  const [selectedCategory, setSelectedCategory] = useState<string>("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    listKnowledgeCategories().then(setCategories).catch(() => {});
  }, []);

  const search = useCallback(async (q: string, cat: string) => {
    setLoading(true);
    try {
      const data = await listKnowledgeEntries({
        search: q || undefined,
        category_id: cat || undefined,
        limit: 50,
      });
      setResults(data);
    } catch {
      setResults([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => search(query, selectedCategory), 300);
    return () => clearTimeout(timer);
  }, [query, selectedCategory, search]);

  return (
    <div>
      {/* Search Bar */}
      <div className="flex items-center gap-2 mb-4">
        <div className="flex-1 relative">
          <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-fg-dimmed" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <input
            type="text"
            placeholder="搜索知识库..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            className="w-full h-11 pl-10 pr-4 rounded-lg bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
          />
        </div>
        <button className="w-11 h-11 flex items-center justify-center rounded-lg bg-accent text-white cursor-pointer border-none hover:brightness-110 transition-all">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/>
            <path d="M19 10v2a7 7 0 0 1-14 0v-2"/>
            <line x1="12" y1="19" x2="12" y2="23"/>
          </svg>
        </button>
      </div>

      {/* Categories */}
      <div className="flex gap-2 overflow-x-auto pb-2 mb-4 scrollbar-none">
        <button
          onClick={() => setSelectedCategory("")}
          className={`shrink-0 h-8 px-3 rounded-full text-xs font-medium transition-all cursor-pointer border-none ${
            !selectedCategory ? "bg-accent text-white" : "bg-surface-alt text-fg-muted hover:text-fg"
          }`}
        >
          全部
        </button>
        {categories.map((cat) => (
          <button
            key={cat.id}
            onClick={() => setSelectedCategory(cat.id)}
            className={`shrink-0 h-8 px-3 rounded-full text-xs font-medium transition-all cursor-pointer border-none ${
              selectedCategory === cat.id ? "bg-accent text-white" : "bg-surface-alt text-fg-muted hover:text-fg"
            }`}
          >
            {cat.name}
          </button>
        ))}
      </div>

      {/* Results */}
      {loading ? (
        <div className="flex justify-center py-12">
          <LoadingSpinner />
        </div>
      ) : results.length === 0 ? (
        <div className="flex flex-col items-center py-16 text-center">
          <span className="text-4xl mb-3">🔍</span>
          <p className="text-sm text-fg-dimmed">暂无搜索结果</p>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {results.map((entry) => (
            <a
              key={entry.id}
              href={`/knowledge/${entry.id}`}
              className="block no-underline rounded-lg border border-border bg-surface p-4 hover:bg-surface-alt transition-colors"
            >
              <h3 className="text-sm font-semibold text-fg-emphasis mb-1 line-clamp-2">
                {entry.title}
              </h3>
              {entry.content && (
                <p className="text-xs text-fg-muted line-clamp-2 mb-2">
                  {entry.content.replace(/<[^>]+>/g, "")}
                </p>
              )}
              <div className="flex items-center gap-2 flex-wrap">
                {entry.category_name && (
                  <Badge>{entry.category_name}</Badge>
                )}
                {entry.tags?.slice(0, 3).map((tag) => (
                  <span key={tag} className="text-[10px] text-fg-dimmed">
                    #{tag}
                  </span>
                ))}
                <span className="text-[10px] text-fg-dimmed ml-auto">
                  v{entry.version ?? 1}
                </span>
              </div>
            </a>
          ))}
        </div>
      )}
    </div>
  );
}
