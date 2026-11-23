-- IX5 ikinci iş yükü: Q1 (publisher JOIN içeren rapor; tüm tamamlanmış satışlar)
SELECT * FROM rpt_q1_monthly_top_publishers ORDER BY sales_month DESC, revenue_rank;
