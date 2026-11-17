/**
 * 단순 표 컴포넌트.
 * @param head  헤더 셀 라벨 배열
 * @param rows  행 배열(각 행은 셀 값 배열)
 * @param empty 행이 없을 때 표시할 안내 문구
 */
export function Table({
  head,
  rows,
  empty,
}: {
  head: string[];
  rows: (string | number)[][];
  empty: string;
}) {
  return (
    <div className="overflow-hidden rounded-lg border">
      <table className="w-full text-sm">
        <thead className="bg-muted/50 text-xs text-muted-foreground">
          <tr>
            {head.map((h) => (
              <th key={h} className="px-3 py-2 text-left font-medium">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td
                colSpan={head.length}
                className="px-3 py-6 text-center text-muted-foreground"
              >
                {empty}
              </td>
            </tr>
          ) : (
            rows.map((r, i) => (
              <tr
                key={i}
                className="border-t border-border/60 transition-colors hover:bg-accent/30"
              >
                {r.map((c, j) => (
                  <td key={j} className="px-3 py-2 tabular-nums">
                    {c}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
